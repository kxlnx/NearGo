package com.hmdp.service.order;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.framework.chain.ChainNode;
import com.hmdp.mapper.VoucherOrderMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 节点五：订单落库，链的终点。
 * 终点不调用 next()，直接 return 结果——这个结果会沿调用栈一层层带回编排层。
 */
@Service
public class SaveOrderNode extends ChainNode<VoucherOrder, OrderCreateContext, OrderCreateOutcome> {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Override
    public OrderCreateOutcome apply(VoucherOrder request, OrderCreateContext context) {
        voucherOrderMapper.insert(request);
        return OrderCreateOutcome.CREATED;                             // 终点产出结果，不再接力
    }
}
