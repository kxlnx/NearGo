package com.hmdp.listener.reconcile;

import com.hmdp.entity.TaskCompensation;
import com.hmdp.framework.rule.AbstractFlowNode;
import com.hmdp.framework.rule.FlowNode;
import com.hmdp.utils.RedisConstants;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;

/**
 * 释放节点：订单已取消时，用回滚脚本幂等释放 Redis 预扣资格与库存。
 * 脚本保证重复执行不会重复回补；释放结果为 null 时抛异常。
 */
@Service
public class ReleaseNode extends AbstractFlowNode<ReservationRequest, ReservationContext, RepairOutcome> {

    /** 与发送失败补偿、关单释放共用同一个回滚脚本（seckill_rollback.lua）。 */
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ReconcileEndNode reconcileEndNode;
    @Resource
    private TaskCompensationRecorder taskCompensationRecorder;

    @Override
    protected RepairOutcome handle(ReservationRequest request, ReservationContext context) {
        Long released = stringRedisTemplate.execute(
                ROLLBACK_SCRIPT,
                Arrays.asList(
                        RedisConstants.SECKILL_STOCK_KEY + context.getVoucherId(),
                        RedisConstants.SECKILL_ORDER_KEY + context.getVoucherId(),
                        RedisConstants.SECKILL_RESERVATION_KEY + context.getVoucherId() + ":" + context.getUserId()
                ),
                context.getUserId().toString()
        );
        if (released == null) {
            throw new IllegalStateException("已取消订单的 Redis 预扣释放失败 reservationKey=" + request.getReservationKey());
        }
        // 预扣已释放：账本闭环为成功
        taskCompensationRecorder.markSuccess(TaskCompensation.BIZ_TYPE_ORDER_CREATE, context.getOrderId());
        context.setOutcome(RepairOutcome.RELEASED);
        return null;
    }

    @Override
    public FlowNode<ReservationRequest, ReservationContext, RepairOutcome> next(ReservationRequest request,
                                                                                ReservationContext context) {
        return reconcileEndNode;
    }
}
