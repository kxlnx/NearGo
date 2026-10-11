package com.hmdp.service.order;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.framework.chain.ChainNode;
import com.hmdp.mapper.VoucherOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 节点一：订单 ID 幂等。同一消息重复消费时直接产出"幂等跳过"，不再往下走。
 */
@Slf4j
@Service
public class OrderIdempotentNode extends ChainNode<VoucherOrder, OrderCreateContext, OrderCreateOutcome> {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Override
    public OrderCreateOutcome apply(VoucherOrder request, OrderCreateContext context) throws Exception {
        if (voucherOrderMapper.selectById(request.getId()) != null) {
            log.info("重复消费订单消息，按订单 ID 幂等返回 orderId={}", request.getId());
            return OrderCreateOutcome.SKIPPED_DUPLICATE_ORDER;   // 不接力：链到此结束（正常结束，不是异常）
        }
        return next(request, context);                           // 接力给下一跳
    }
}
