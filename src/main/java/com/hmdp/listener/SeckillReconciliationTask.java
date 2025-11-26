package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.utils.RedisConstants;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

/**
 * 秒杀对账与补偿任务：
 * 1. 发现 Redis 已预扣但数据库订单未落库时，使用原订单 ID 重新投递 RocketMQ；
 * 2. 发现订单已经落库但 Redis 预占记录缺失时，补回用户预占集合；
 * 3. 发现订单已经超时关闭时，幂等释放 Redis 预扣库存；
 * 4. 对 Redis 预扣用户数与数据库有效订单数进行周期性对账。
 */
@Component
@Slf4j
public class SeckillReconciliationTask {
    private static final String ORDER_ID_FIELD = "orderId";
    private static final String USER_ID_FIELD = "userId";
    private static final String VOUCHER_ID_FIELD = "voucherId";
    private static final String RESERVED_AT_FIELD = "reservedAt";
    private static final String RETRY_COUNT_FIELD = "retryCount";
    private static final String LAST_RETRY_AT_FIELD = "lastRetryAt";

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT;

    static {
        RELEASE_SCRIPT = new DefaultRedisScript<>();
        RELEASE_SCRIPT.setLocation(new ClassPathResource("seckill_release.lua"));
        RELEASE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Value("${seckill.reconcile.enabled:true}")
    private boolean enabled;

    @Value("${seckill.rocketmq.order-topic:seckill-order-topic}")
    private String orderTopic;

    /** 消息刚发送后给消费者留出的正常落库时间，避免过早重复投递。 */
    @Value("${seckill.reconcile.stale-seconds:30}")
    private long staleSeconds;

    /** 同一个未落库订单两次自动重投之间的最小间隔。 */
    @Value("${seckill.reconcile.retry-interval-seconds:60}")
    private long retryIntervalSeconds;

    @XxlJob("seckillReconcileJob")
    public void reconcile() {
        if (!enabled) {
            return;
        }

        boolean hasFailure = false;
        try {
            hasFailure = repairReservations();
            reconcileActiveOrderCounts();
            if (hasFailure) {
                // 显式抛出异常，让 XXL-JOB 将本次执行记录为失败并按配置重试。
                throw new IllegalStateException("秒杀对账存在未完成的自动补偿");
            }
        } catch (Exception e) {
            log.error("秒杀对账任务执行失败", e);
            // 不能吞掉异常，否则 XXL-JOB 会把失败任务误判为成功。
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new IllegalStateException(e);
        }
    }

    /** 扫描 Lua 产生的预扣元数据，进行重投、补回或释放。 */
    private boolean repairReservations() {
        boolean hasFailure = false;
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                ScanOptions.scanOptions()
                        .match(RedisConstants.SECKILL_RESERVATION_KEY + "*")
                        .count(100)
                        .build())) {
            while (cursor.hasNext()) {
                String reservationKey = cursor.next();
                try {
                    Map<Object, Object> fields = stringRedisTemplate.opsForHash().entries(reservationKey);
                    Long orderId = parseLong(fields.get(ORDER_ID_FIELD));
                    Long userId = parseLong(fields.get(USER_ID_FIELD));
                    Long voucherId = parseLong(fields.get(VOUCHER_ID_FIELD));
                    Long reservedAt = parseLong(fields.get(RESERVED_AT_FIELD));
                    if (orderId == null || userId == null || voucherId == null || reservedAt == null) {
                        log.error("秒杀预扣元数据不完整 reservationKey={}, fields={}", reservationKey, fields);
                        hasFailure = true;
                        continue;
                    }

                    VoucherOrder order = voucherOrderMapper.selectById(orderId);
                    String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;

                    if (order == null) {
                        if (!shouldRetry(fields, reservedAt)) {
                            continue;
                        }

                        try {
                            VoucherOrder retryOrder = new VoucherOrder()
                                    .setId(orderId)
                                    .setUserId(userId)
                                    .setVoucherId(voucherId);
                            rocketMQTemplate.syncSend(orderTopic, JSONUtil.toJsonStr(retryOrder), 3000);
                            stringRedisTemplate.opsForHash().increment(
                                    reservationKey, RETRY_COUNT_FIELD, 1);
                            stringRedisTemplate.opsForHash().put(
                                    reservationKey, LAST_RETRY_AT_FIELD,
                                    String.valueOf(Instant.now().getEpochSecond()));
                            log.warn("秒杀订单未落库，已自动补偿重投 orderId={}, voucherId={}, userId={}",
                                    orderId, voucherId, userId);
                        } catch (Exception e) {
                            hasFailure = true;
                            log.error("秒杀订单自动补偿重投失败 orderId={}, voucherId={}, userId={}",
                                    orderId, voucherId, userId, e);
                        }
                        continue;
                    }

                    if (Integer.valueOf(4).equals(order.getStatus())) {
                        // 已取消订单不能继续占用 Redis 的一人一单资格和库存。
                        Long released = releaseRedisReservation(order.getVoucherId(), order.getUserId());
                        if (released == null) {
                            hasFailure = true;
                            log.error("已取消订单的 Redis 预扣释放失败 orderId={}", orderId);
                        } else {
                            log.info("已取消订单完成 Redis 预扣修复 orderId={}, released={}", orderId, released);
                        }
                    } else {
                        // 订单已经落库但 Redis 用户集合缺失时，补回一人一单标记。
                        stringRedisTemplate.opsForSet().add(orderKey, userId.toString());
                        stringRedisTemplate.delete(reservationKey);
                        log.info("已落库订单完成 Redis 预占记录修复 orderId={}", orderId);
                    }
                } catch (Exception e) {
                    hasFailure = true;
                    log.error("处理秒杀预扣记录失败 reservationKey={}", reservationKey, e);
                }
            }
        } catch (Exception e) {
            log.error("扫描秒杀预扣记录失败", e);
            return true;
        }
        return hasFailure;
    }

    /** 对账只统计未取消订单，避免已取消订单仍被 COUNT 进有效库存占用。 */
    private void reconcileActiveOrderCounts() {
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                ScanOptions.scanOptions()
                        .match(RedisConstants.SECKILL_ORDER_KEY + "*")
                        .count(100)
                        .build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String voucherIdText = key.substring(RedisConstants.SECKILL_ORDER_KEY.length());
                long voucherId = Long.parseLong(voucherIdText);
                Long redisUsers = stringRedisTemplate.opsForSet().size(key);
                Long dbActiveOrders = voucherOrderMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, voucherId)
                        .ne(VoucherOrder::getStatus, 4));
                if (redisUsers != null && dbActiveOrders != null
                        && redisUsers.longValue() != dbActiveOrders.longValue()) {
                    log.warn("秒杀对账不一致 voucherId={}, redisUsers={}, dbActiveOrders={}",
                            voucherId, redisUsers, dbActiveOrders);
                }
            }
        } catch (Exception e) {
            log.error("秒杀有效订单数量对账失败", e);
            throw new IllegalStateException(e);
        }
    }

    private boolean shouldRetry(Map<Object, Object> fields, long reservedAt) {
        long now = Instant.now().getEpochSecond();
        if (now - reservedAt < staleSeconds) {
            return false;
        }
        Long lastRetryAt = parseLong(fields.get(LAST_RETRY_AT_FIELD));
        return lastRetryAt == null || now - lastRetryAt >= retryIntervalSeconds;
    }

    private Long releaseRedisReservation(Long voucherId, Long userId) {
        return stringRedisTemplate.execute(
                RELEASE_SCRIPT,
                Arrays.asList(
                        RedisConstants.SECKILL_STOCK_KEY + voucherId,
                        RedisConstants.SECKILL_ORDER_KEY + voucherId,
                        RedisConstants.SECKILL_RESERVATION_KEY + voucherId + ":" + userId
                ),
                userId.toString()
        );
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
