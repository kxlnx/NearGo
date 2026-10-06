package com.hmdp.service.order;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.framework.chain.ChainNode;
import com.hmdp.service.ISeckillVoucherService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 节点四：数据库条件扣库存。
 * 只有 stock > 0 才更新成功，即使 Redis 数据被误改，数据库库存也不会扣成负数。
 */
@Service
public class StockDeductNode implements ChainNode<VoucherOrder, OrderCreateContext> {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Override
    public boolean process(VoucherOrder request, OrderCreateContext context) {
        boolean stockUpdated = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, request.getVoucherId())
                        .gt(SeckillVoucher::getStock, 0)
                        .setSql("stock = stock - 1")
        );
        if (!stockUpdated) {
            throw new IllegalStateException("数据库库存与 Redis 预扣状态不一致");
        }
        return true;
    }
}
