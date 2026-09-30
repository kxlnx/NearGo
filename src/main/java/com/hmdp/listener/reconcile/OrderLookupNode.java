package com.hmdp.listener.reconcile;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.framework.rule.AbstractFlowNode;
import com.hmdp.framework.rule.FlowNode;
import com.hmdp.mapper.VoucherOrderMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 查库节点：加载订单进上下文，并按订单状态决定下一跳。
 * 三个分支集中在这里声明，就是整棵树的"岔路口"。
 */
@Service
public class OrderLookupNode extends AbstractFlowNode<ReservationRequest, ReservationContext, RepairOutcome> {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    @Resource
    private RepostNode repostNode;
    @Resource
    private ReleaseNode releaseNode;
    @Resource
    private RepairNode repairNode;

    @Override
    protected RepairOutcome handle(ReservationRequest request, ReservationContext context) {
        VoucherOrder order = voucherOrderMapper.selectById(context.getOrderId());
        context.setOrder(order);
        return null;
    }

    @Override
    public FlowNode<ReservationRequest, ReservationContext, RepairOutcome> next(ReservationRequest request,
                                                                                ReservationContext context) {
        if (context.getOrder() == null) {
            return repostNode;                          // 未落库 → 重投分支
        }
        if (Integer.valueOf(4).equals(context.getOrder().getStatus())) {
            return releaseNode;                         // 已取消 → 释放分支
        }
        return repairNode;                              // 已落库 → 补标记分支
    }
}
