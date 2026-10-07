package com.hmdp.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.framework.rule.FlowEngine;
import com.hmdp.listener.reconcile.RepairOutcome;
import com.hmdp.listener.reconcile.ReservationContext;
import com.hmdp.listener.reconcile.ReservationRequest;
import com.hmdp.listener.reconcile.ReservationRootNode;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.utils.RedisConstants;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 秒杀对账与补偿任务：
 * 1. 发现 Redis 已预扣但数据库订单未落库时，使用原订单 ID 重新投递 RocketMQ；
 * 2. 发现订单已经落库但 Redis 预占记录缺失时，补回用户预占集合；
 * 3. 发现订单已经超时关闭时，幂等释放 Redis 预扣库存；
 * 4. 对 Redis 预扣用户数与数据库有效订单数进行周期性对账。
 *
 * 单条预扣记录的处置逻辑（重投 / 释放 / 补标记 / 跳过）由规则树编排：
 * 本类只负责扫描、驱动引擎与结果统计，分支声明见
 * {@link com.hmdp.listener.reconcile.OrderLookupNode#next}。
 */
@Component
@Slf4j
public class SeckillReconciliationTask {

    /** 单条记录的规则树步数上限：当前最长路径为 4 个节点，留出冗余防止分支装配错误导致路径爆炸。 */
    private static final int RECONCILE_MAX_STEPS = 16;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    @Resource
    private ReservationRootNode reservationRootNode;

    @Value("${seckill.reconcile.enabled:true}")
    private boolean enabled;

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

    /** 扫描 Lua 产生的预扣元数据，逐条交给规则树处置。 */
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
                    ReservationContext context = new ReservationContext();
                    RepairOutcome outcome = FlowEngine.run(
                            reservationRootNode,
                            new ReservationRequest(reservationKey, fields),
                            context,
                            RECONCILE_MAX_STEPS);
                    logOutcome(reservationKey, outcome, context);
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

    /** 按规则树产出统一记录每条记录的处置结果。 */
    private void logOutcome(String reservationKey, RepairOutcome outcome, ReservationContext context) {
        if (outcome == null) {
            log.warn("秒杀预扣记录未产出处置结果 reservationKey={}", reservationKey);
            return;
        }
        switch (outcome) {
            case REPOSTED:
                log.warn("秒杀订单未落库，已自动补偿重投 orderId={}, voucherId={}, userId={}",
                        context.getOrderId(), context.getVoucherId(), context.getUserId());
                break;
            case RELEASED:
                log.info("已取消订单完成 Redis 预扣修复 orderId={}", context.getOrderId());
                break;
            case REPAIRED:
                log.info("已落库订单完成 Redis 预占记录修复 orderId={}", context.getOrderId());
                break;
            case FAILED_MANUAL:
                log.error("秒杀订单补偿已转人工处理 orderId={}, voucherId={}, userId={}",
                        context.getOrderId(), context.getVoucherId(), context.getUserId());
                break;
            case SKIPPED:
            default:
                log.debug("秒杀预扣记录本轮无需处理 reservationKey={}", reservationKey);
        }
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
}
