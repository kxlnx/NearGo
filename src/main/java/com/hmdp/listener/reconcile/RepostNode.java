package com.hmdp.listener.reconcile;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.TaskCompensation;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.framework.rule.AbstractFlowNode;
import com.hmdp.framework.rule.FlowNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Instant;

/**
 * 重投节点：订单未落库且已到重投窗口时，用原订单 ID 重新投递 RocketMQ。
 * 未到窗口 → SKIPPED；投递成功 → REPOSTED；投递失败 → 抛异常；
 * 重投次数达到上限 → FAILED_MANUAL（转人工，账本标记终态失败）。
 */
@Slf4j
@Service
public class RepostNode extends AbstractFlowNode<ReservationRequest, ReservationContext, RepairOutcome> {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private ReconcileEndNode reconcileEndNode;
    @Resource
    private TaskCompensationRecorder taskCompensationRecorder;

    @Value("${seckill.rocketmq.order-topic:seckill-order-topic}")
    private String orderTopic;

    /** 消息刚发送后给消费者留出的正常落库时间，避免过早重复投递。 */
    @Value("${seckill.reconcile.stale-seconds:30}")
    private long staleSeconds;

    /** 同一个未落库订单两次自动重投之间的最小间隔。 */
    @Value("${seckill.reconcile.retry-interval-seconds:60}")
    private long retryIntervalSeconds;

    /** 自动重投次数上限：达到后不再自动重试，账本标记失败并转人工。 */
    @Value("${seckill.reconcile.max-retry-count:5}")
    private long maxRetryCount;

    @Override
    protected RepairOutcome handle(ReservationRequest request, ReservationContext context) {
        long retryCount = currentRetryCount(request);
        if (retryCount >= maxRetryCount) {
            // 达到人工介入阈值：停止自动重投，账本标记终态失败
            taskCompensationRecorder.markFailed(TaskCompensation.BIZ_TYPE_ORDER_CREATE, context.getOrderId(),
                    "自动重投超过上限 " + maxRetryCount + " 次");
            log.warn("秒杀订单补偿超过最大重试次数，转人工处理 orderId={}, retryCount={}",
                    context.getOrderId(), retryCount);
            context.setOutcome(RepairOutcome.FAILED_MANUAL);
            return null;
        }
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
        long newRetryCount = retryCount + 1;
        stringRedisTemplate.opsForHash().increment(request.getReservationKey(),
                ReservationRequest.RETRY_COUNT_FIELD, 1);
        stringRedisTemplate.opsForHash().put(request.getReservationKey(),
                ReservationRequest.LAST_RETRY_AT_FIELD, String.valueOf(Instant.now().getEpochSecond()));
        // DB 账本同步记录重投次数与下次执行时间：Redis 数据丢失后仍可审计
        taskCompensationRecorder.markRetrying(TaskCompensation.BIZ_TYPE_ORDER_CREATE,
                context.getOrderId(), (int) newRetryCount, null);
        context.setOutcome(RepairOutcome.REPOSTED);
        return null;
    }

    private long currentRetryCount(ReservationRequest request) {
        Long count = request.longField(ReservationRequest.RETRY_COUNT_FIELD);
        return count == null ? 0 : count;
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
