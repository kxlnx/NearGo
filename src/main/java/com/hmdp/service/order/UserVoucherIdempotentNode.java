package com.hmdp.service.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.framework.chain.ChainNode;
import com.hmdp.mapper.VoucherOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 节点三：用户 + 券业务幂等。
 * 已取消（status=4）的订单不计入重复——取消时已释放库存与资格，允许重新抢购。
 */
@Slf4j
@Service
public class UserVoucherIdempotentNode extends ChainNode<VoucherOrder, OrderCreateContext, OrderCreateOutcome> {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Override
    public OrderCreateOutcome apply(VoucherOrder request, OrderCreateContext context) throws Exception {
        Long duplicated = voucherOrderMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getUserId, request.getUserId())
                .eq(VoucherOrder::getVoucherId, request.getVoucherId())
                .ne(VoucherOrder::getStatus, 4));
        if (duplicated != null && duplicated > 0) {
            log.info("重复消费用户券消息，按用户和券幂等返回 userId={}, voucherId={}",
                    request.getUserId(), request.getVoucherId());
            return OrderCreateOutcome.SKIPPED_DUPLICATE_USER_VOUCHER;   // 不接力：链到此结束
        }
        return next(request, context);                                   // 接力
    }
}
