package com.hmdp.service.order;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.framework.chain.ChainNode;
import com.hmdp.mapper.VoucherOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 节点一：订单 ID 幂等。同一消息重复消费时直接结束链路。
 */
@Slf4j
@Service
public class OrderIdempotentNode implements ChainNode<VoucherOrder, OrderCreateContext> {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Override
    public boolean process(VoucherOrder request, OrderCreateContext context) {
        if (voucherOrderMapper.selectById(request.getId()) != null) {
            log.info("重复消费订单消息，按订单 ID 幂等返回 orderId={}", request.getId());
            return false;
        }
        return true;
    }
}
