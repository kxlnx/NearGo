package com.hmdp.listener;

import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import javax.annotation.Resource;

/** RocketMQ 延迟消息消费者：未支付订单条件关单并释放一次库存。 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "seckill.consumer", name = "enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${seckill.rocketmq.timeout-topic:order-timeout-topic}",
        consumerGroup = "${seckill.rocketmq.timeout-consumer-group:order-timeout-consumer}",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING
)
public class OrderTimeoutListener implements RocketMQListener<String> {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(String orderId) {
        boolean closed = voucherOrderService.closeTimeoutOrder(Long.valueOf(orderId));
        log.info("RocketMQ 延迟关单 orderId={}, closed={}", orderId, closed);
    }
}
