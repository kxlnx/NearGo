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
 * 锁由编排层在 finally 中统一释放。
 */
@Service
public class UserLockNode implements ChainNode<VoucherOrder, OrderCreateContext> {

    @Resource
    private RedissonClient redissonClient;

    @Override
    public boolean process(VoucherOrder request, OrderCreateContext context) {
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + request.getUserId());
        if (!lock.tryLock()) {
            throw new IllegalStateException("用户订单并发锁获取失败");
        }
        context.setUserLock(lock);
        return true;
    }
}
