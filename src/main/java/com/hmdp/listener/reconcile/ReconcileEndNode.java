package com.hmdp.listener.reconcile;

import com.hmdp.framework.rule.AbstractFlowNode;
import com.hmdp.framework.rule.FlowNode;
import org.springframework.stereotype.Service;

/**
 * 终点节点：统一收口，返回本次处置结果；next 返回 null 表示链到头。
 */
@Service
public class ReconcileEndNode extends AbstractFlowNode<ReservationRequest, ReservationContext, RepairOutcome> {

    @Override
    protected RepairOutcome handle(ReservationRequest request, ReservationContext context) {
        return context.getOutcome();
    }

    @Override
    public FlowNode<ReservationRequest, ReservationContext, RepairOutcome> next(ReservationRequest request,
                                                                                ReservationContext context) {
        return null;
    }
}
