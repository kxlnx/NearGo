package com.hmdp.service.order;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.framework.chain.ChainNode;
import com.hmdp.utils.RedisConstants;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 节点二：按用户加 Redisson 锁，防止同一用户的多条消息并发穿过后续幂等校验。
 * 节点只负责申请锁并写进上下文，锁由编排层在 finally 中统一释放。
 */
@Service
public class UserLockNode extends ChainNode<VoucherOrder, OrderCreateContext, OrderCreateOutcome> {

    @Resource
    private RedissonClient redissonClient;

    @Override
    public OrderCreateOutcome apply(VoucherOrder request, OrderCreateContext context) throws Exception {
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + request.getUserId());
        if (!lock.tryLock()) {
            throw new IllegalStateException("用户订单并发锁获取失败");
        }
        context.setUserLock(lock);                               // 写上下文：把"要还的东西"交给编排层
        return next(request, context);                           // 接力
    }
}
