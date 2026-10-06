package com.hmdp.service.order;

import com.hmdp.entity.VoucherOrder;
import lombok.Data;
import org.redisson.api.RLock;

/**
 * 订单创建链的上下文：链上节点写入/读取，编排层负责释放锁等资源。
 */
@Data
public class OrderCreateContext {

    /** 本次要落库的订单（只读）。 */
    private final VoucherOrder order;

    /** 用户维度并发锁（UserLockNode 获取，编排层 finally 中释放）。 */
    private RLock userLock;

    /** 释放节点申请的锁；未加锁时为空操作。 */
    public void releaseLock() {
        if (userLock != null && userLock.isHeldByCurrentThread()) {
            userLock.unlock();
        }
    }
}
