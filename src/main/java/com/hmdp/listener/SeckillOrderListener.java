package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/** RocketMQ 秒杀订单消费者：异步完成数据库订单落库。 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "seckill.consumer", name = "enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${seckill.rocketmq.order-topic:seckill-order-topic}",
        consumerGroup = "${seckill.rocketmq.order-consumer-group:seckill-order-consumer}",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING
)
public class SeckillOrderListener implements RocketMQListener<String> {
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Override
    public void onMessage(String message) {
        VoucherOrder order = JSONUtil.toBean(message, VoucherOrder.class);
        voucherOrderService.createVoucherOrder(order);
        log.info("RocketMQ 秒杀订单处理完成 orderId={}, voucherId={}", order.getId(), order.getVoucherId());
    }
}
