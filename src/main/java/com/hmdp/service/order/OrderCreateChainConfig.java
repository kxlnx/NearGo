package com.hmdp.service.order;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.framework.chain.Chain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * 订单创建链装配：顺序显式声明在这一处，新增校验/处理只需加节点并插入链。
 */
@Configuration
public class OrderCreateChainConfig {

    @Bean
    public Chain<VoucherOrder, OrderCreateContext> orderCreateChain(
            OrderIdempotentNode orderIdempotentNode,
            UserLockNode userLockNode,
            UserVoucherIdempotentNode userVoucherIdempotentNode,
            StockDeductNode stockDeductNode,
            SaveOrderNode saveOrderNode) {
        return new Chain<>(Arrays.asList(
                orderIdempotentNode,
                userLockNode,
                userVoucherIdempotentNode,
                stockDeductNode,
                saveOrderNode));
    }
}
