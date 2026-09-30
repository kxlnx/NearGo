package com.hmdp.listener.reconcile;

import com.hmdp.entity.VoucherOrder;
import lombok.Data;

/**
 * 一次对账处理过程中的上下文：规则树节点写，后面的节点与任务读。
 * 铁律：节点是单例，任何"一次请求的数据"都必须放在这里，不能放节点成员变量。
 */
@Data
public class ReservationContext {

    /** 解析后的预扣元数据（ReservationRootNode 写入）。 */
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private Long reservedAt;

    /** 数据库订单（OrderLookupNode 写入）。 */
    private VoucherOrder order;

    /** 最终处置结果（各处置节点写入，ReconcileEndNode 返回）。 */
    private RepairOutcome outcome;
}
