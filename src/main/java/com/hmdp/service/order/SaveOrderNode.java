package com.hmdp.service.order;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.framework.chain.ChainNode;
import com.hmdp.mapper.VoucherOrderMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 节点五：订单落库，链路终点。
 */
@Service
public class SaveOrderNode implements ChainNode<VoucherOrder, OrderCreateContext> {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Override
    public boolean process(VoucherOrder request, OrderCreateContext context) {
        voucherOrderMapper.insert(request);
        return true;
    }
}
