package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * 秒杀开关缓存同步：订阅 Redis Pub/Sub 变更通知，收到后失效本地缓存。
 *
 * <p>与 SeckillSwitchManager 的 5 秒 TTL 组成"推 + 拉兜底"：
 * 推送正常时毫秒级生效；通知丢失（断连窗口）时最迟 5 秒自愈。
 */
@Component
@Slf4j
public class SeckillSwitchCacheSync {

    @Resource
    private RedissonClient redissonClient;
    @Resource
    private SeckillSwitchManager seckillSwitchManager;

    @PostConstruct
    public void subscribe() {
        redissonClient.getTopic(RedisConstants.SECKILL_DYNAMIC_CONFIG_TOPIC)
                .addListener(String.class, (channel, message) -> {
                    seckillSwitchManager.invalidateLocalCache();
                    log.info("收到秒杀开关变更通知，已失效本地缓存 channel={}", channel);
                });
        log.info("秒杀开关变更通知订阅完成 topic={}", RedisConstants.SECKILL_DYNAMIC_CONFIG_TOPIC);
    }
}
