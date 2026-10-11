package com.hmdp.service.order;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.framework.chain.Chain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 订单创建链装配：顺序显式声明在这一处。
 *
 * <p>链是静态结构，所以串完立刻 {@link Chain#assemble}——装配期做一次静态判环，
 * 写错（指回自己、指回前面某个节点）会在<b>启动时</b>就抛异常并打印环路路径，
 * 而不是等到线上某次请求把栈打爆。
 */
@Configuration
public class OrderCreateChainConfig {

    @Bean
    public Chain<VoucherOrder, OrderCreateContext, OrderCreateOutcome> orderCreateChain(
            OrderIdempotentNode orderIdempotentNode,
            UserLockNode userLockNode,
            UserVoucherIdempotentNode userVoucherIdempotentNode,
            StockDeductNode stockDeductNode,
            SaveOrderNode saveOrderNode) {
        // 顺序 = 业务语义：先查重（最便宜）→ 再串行化 → 再业务查重 → 再动数据库资源
        orderIdempotentNode.setNext(userLockNode)
                .setNext(userVoucherIdempotentNode)
                .setNext(stockDeductNode)
                .setNext(saveOrderNode);
        return Chain.assemble(orderIdempotentNode);
    }
}
