package com.hmdp.service.order;

/**
 * 订单创建链的产出：正常创建，或者"为什么没走到落库"。
 *
 * <p>用枚举替代原来的 boolean，是为了让提前结束的原因变成一个<b>可记录、可统计</b>的事实：
 * 编排层能直接打出 {@code outcome=SKIPPED_DUPLICATE_ORDER}，而 boolean 只能说"false"。
 */
public enum OrderCreateOutcome {

    /** 正常创建：条件扣库存 + 订单落库都已完成 */
    CREATED,

    /** 订单 ID 幂等命中：同一条消息被重复投递（订单 ID 相同），链路提前结束 */
    SKIPPED_DUPLICATE_ORDER,

    /** 用户 + 券幂等命中：同一用户对同一张券已存在有效订单，链路提前结束 */
    SKIPPED_DUPLICATE_USER_VOUCHER
}
