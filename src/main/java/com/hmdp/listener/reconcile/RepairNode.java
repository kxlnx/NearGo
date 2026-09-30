package com.hmdp.listener.reconcile;

import com.hmdp.framework.rule.AbstractFlowNode;
import com.hmdp.framework.rule.FlowNode;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 修复节点：订单已落库时，补回一人一单标记（防重复下单资格），并清理预扣记录。
 */
@Service
public class RepairNode extends AbstractFlowNode<ReservationRequest, ReservationContext, RepairOutcome> {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ReconcileEndNode reconcileEndNode;

    @Override
    protected RepairOutcome handle(ReservationRequest request, ReservationContext context) {
        stringRedisTemplate.opsForSet().add(
                RedisConstants.SECKILL_ORDER_KEY + context.getVoucherId(),
                context.getUserId().toString());
        stringRedisTemplate.delete(request.getReservationKey());
        context.setOutcome(RepairOutcome.REPAIRED);
        return null;
    }

    @Override
    public FlowNode<ReservationRequest, ReservationContext, RepairOutcome> next(ReservationRequest request,
                                                                                ReservationContext context) {
        return reconcileEndNode;
    }
}
