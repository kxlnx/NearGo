package com.hmdp.listener.reconcile;

import com.hmdp.framework.rule.AbstractFlowNode;
import com.hmdp.framework.rule.FlowNode;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 根节点：校验预扣元数据完整性，并把数据放进上下文。
 * 元数据不完整时抛异常，由任务循环按"单条失败"统计。
 */
@Service
public class ReservationRootNode extends AbstractFlowNode<ReservationRequest, ReservationContext, RepairOutcome> {

    @Resource
    private OrderLookupNode orderLookupNode;

    @Override
    protected RepairOutcome handle(ReservationRequest request, ReservationContext context) {
        context.setOrderId(request.longField(ReservationRequest.ORDER_ID_FIELD));
        context.setUserId(request.longField(ReservationRequest.USER_ID_FIELD));
        context.setVoucherId(request.longField(ReservationRequest.VOUCHER_ID_FIELD));
        context.setReservedAt(request.longField(ReservationRequest.RESERVED_AT_FIELD));
        if (context.getOrderId() == null || context.getUserId() == null
                || context.getVoucherId() == null || context.getReservedAt() == null) {
            throw new IllegalStateException("秒杀预扣元数据不完整 reservationKey=" + request.getReservationKey()
                    + ", fields=" + request.getFields());
        }
        return null;
    }

    @Override
    public FlowNode<ReservationRequest, ReservationContext, RepairOutcome> next(ReservationRequest request,
                                                                                ReservationContext context) {
        return orderLookupNode;
    }
}
