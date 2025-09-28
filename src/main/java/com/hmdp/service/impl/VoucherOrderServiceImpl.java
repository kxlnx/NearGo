package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;

/**
 * 秒杀订单服务：Redis Lua 负责资格校验和预扣，RocketMQ 负责异步落库。
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        COMPENSATE_SCRIPT = new DefaultRedisScript<>();
        COMPENSATE_SCRIPT.setLocation(new ClassPathResource("seckill_compensate.lua"));
        COMPENSATE_SCRIPT.setResultType(Long.class);

    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private RedissonClient redissonClient;

    @Value("${seckill.rocketmq.order-topic:seckill-order-topic}")
    private String orderTopic;

    /**
     * 成功表示已经通过 Redis 原子资格校验并进入 RocketMQ 排队，不代表数据库订单已经落库。
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                user.getId().toString(),
                String.valueOf(orderId)
        );
        int code = result == null ? -1 : result.intValue();
        if (code != 0) {
            if (code == 1) {
                return Result.fail("库存不足");
            }
            if (code == 2) {
                return Result.fail("不能重复下单");
            }
            return Result.fail("秒杀库存未初始化");
        }

        VoucherOrder order = new VoucherOrder()
                .setId(orderId)
                .setUserId(user.getId())
                .setVoucherId(voucherId);
        try {
            // 同步发送确认 Broker 已接收消息；确认失败时回滚本次 Redis 预扣资格。
            rocketMQTemplate.syncSend(orderTopic, JSONUtil.toJsonStr(order), 3000);
        } catch (Exception e) {
            Long compensated = stringRedisTemplate.execute(
                    COMPENSATE_SCRIPT,
                    Arrays.asList(
                            RedisConstants.SECKILL_STOCK_KEY + voucherId,
                            RedisConstants.SECKILL_ORDER_KEY + voucherId,
                            reservationKey(voucherId, user.getId())
                    ),
                    user.getId().toString()
            );
            log.error("RocketMQ 发送失败，Redis 预扣补偿 orderId={}, compensated={}", orderId, compensated, e);
            return Result.fail("排队失败，请重试");
        }
        return Result.ok(orderId);
    }

    /** RocketMQ 消费端事务：订单 ID 幂等 + 用户/券幂等 + 数据库条件扣库存。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        if (getById(voucherOrder.getId()) != null) {
            log.info("重复消费订单消息，按订单 ID 幂等返回 orderId={}", voucherOrder.getId());
            return;
        }

        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean locked = lock.tryLock();
        if (!locked) {
            throw new IllegalStateException("用户订单并发锁获取失败");
        }
        try {
            long duplicated = count(new LambdaQueryWrapper<VoucherOrder>()
                    .eq(VoucherOrder::getUserId, userId)
                    .eq(VoucherOrder::getVoucherId, voucherOrder.getVoucherId()));
            if (duplicated > 0) {
                log.info("重复消费用户券消息，按用户和券幂等返回 userId={}, voucherId={}",
                        userId, voucherOrder.getVoucherId());
                return;
            }

            boolean stockUpdated = seckillVoucherService.update(
                    new LambdaUpdateWrapper<SeckillVoucher>()
                            .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                            .gt(SeckillVoucher::getStock, 0)
                            .setSql("stock = stock - 1")
            );
            if (!stockUpdated) {
                throw new IllegalStateException("数据库库存与 Redis 预扣状态不一致");
            }
            save(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    /** 保留同步数据库实现，作为压测控制组，不作为正式秒杀入口。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result getResult(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        long duplicated = count(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .eq(VoucherOrder::getUserId, user.getId()));
        if (duplicated > 0) {
            return Result.fail("禁止重复购买");
        }
        boolean stockUpdated = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, voucherId)
                        .gt(SeckillVoucher::getStock, 0)
                        .setSql("stock = stock - 1")
        );
        if (!stockUpdated) {
            return Result.fail("库存不足");
        }
        VoucherOrder order = new VoucherOrder()
                .setId(redisIdWorker.nextId("order"))
                .setUserId(user.getId())
                .setVoucherId(voucherId);
        save(order);
        return Result.ok(order.getId());
    }

    private static String reservationKey(Long voucherId, Long userId) {
        return RedisConstants.SECKILL_RESERVATION_KEY + voucherId + ":" + userId;
    }
}
