package com.hmdp.listener.reconcile;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.framework.rule.AbstractFlowNode;
import com.hmdp.framework.rule.FlowNode;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Instant;

/**
 * 重投节点：订单未落库且已到重投窗口时，用原订单 ID 重新投递 RocketMQ。
 * 未到窗口 → SKIPPED；投递成功 → REPOSTED；投递失败 → 抛异常（任务按单条失败统计）。
 */
@Service
public class RepostNode extends AbstractFlowNode<ReservationRequest, ReservationContext, RepairOutcome> {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private ReconcileEndNode reconcileEndNode;

    @Value("${seckill.rocketmq.order-topic:seckill-order-topic}")
    private String orderTopic;

    /** 消息刚发送后给消费者留出的正常落库时间，避免过早重复投递。 */
    @Value("${seckill.reconcile.stale-seconds:30}")
    private long staleSeconds;

    /** 同一个未落库订单两次自动重投之间的最小间隔。 */
    @Value("${seckill.reconcile.retry-interval-seconds:60}")
    private long retryIntervalSeconds;

    @Override
    protected RepairOutcome handle(ReservationRequest request, ReservationContext context) {
        if (!shouldRetry(request, context.getReservedAt())) {
            context.setOutcome(RepairOutcome.SKIPPED);
            return null;
        }
        VoucherOrder retryOrder = new VoucherOrder()
                .setId(context.getOrderId())
                .setUserId(context.getUserId())
                .setVoucherId(context.getVoucherId());
        try {
            rocketMQTemplate.syncSend(orderTopic, JSONUtil.toJsonStr(retryOrder), 3000);
        } catch (Exception e) {
            throw new IllegalStateException("秒杀订单自动补偿重投失败 reservationKey=" + request.getReservationKey(), e);
        }
        stringRedisTemplate.opsForHash().increment(request.getReservationKey(),
                ReservationRequest.RETRY_COUNT_FIELD, 1);
        stringRedisTemplate.opsForHash().put(request.getReservationKey(),
                ReservationRequest.LAST_RETRY_AT_FIELD, String.valueOf(Instant.now().getEpochSecond()));
        context.setOutcome(RepairOutcome.REPOSTED);
        return null;
    }

    private boolean shouldRetry(ReservationRequest request, long reservedAt) {
        long now = Instant.now().getEpochSecond();
        if (now - reservedAt < staleSeconds) {
            return false;
        }
        Long lastRetryAt = request.longField(ReservationRequest.LAST_RETRY_AT_FIELD);
        return lastRetryAt == null || now - lastRetryAt >= retryIntervalSeconds;
    }

    @Override
    public FlowNode<ReservationRequest, ReservationContext, RepairOutcome> next(ReservationRequest request,
                                                                                ReservationContext context) {
        return reconcileEndNode;
    }
}
