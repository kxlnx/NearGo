package com.hmdp.listener.reconcile;

/**
 * 单条秒杀预扣记录的处置结果，由规则树终点产出，供任务统一日志与统计。
 */
public enum RepairOutcome {

    /** 未到重投窗口，本轮不处理。 */
    SKIPPED,

    /** 订单未落库，已重新投递 RocketMQ。 */
    REPOSTED,

    /** 订单已取消，已释放 Redis 预扣资格与库存。 */
    RELEASED,

    /** 订单已落库，已补回一人一单标记并清理预扣记录。 */
    REPAIRED,

    /** 自动重投达到上限，已转人工处理，不再自动重试。 */
    FAILED_MANUAL
}
