package com.hmdp.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Data;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀动态开关与灰度切量：
 * Redis Hash 保存配置（enabled / cutRange），本地 Caffeine 缓存 5 秒，避免每个秒杀请求都去读配置。
 * 配置缺失时按"全量开放"兜底，保证历史行为不变。
 */
@Component
public class SeckillSwitchManager {

    /** 本地缓存有效期：开关/切量调整后，单实例最多 5 秒生效。 */
    private static final long LOCAL_TTL_SECONDS = 5L;
    private static final String CACHE_KEY = "seckill-switch";
    private static final String FIELD_ENABLED = "enabled";
    private static final String FIELD_CUT_RANGE = "cutRange";
    private static final int FULL_CUT_RANGE = 100;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final Cache<String, SeckillSwitch> localCache = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(LOCAL_TTL_SECONDS, TimeUnit.SECONDS)
            .build();

    /** 返回 null 表示放行，否则返回拒绝原因。 */
    public String rejectReason(Long userId) {
        SeckillSwitch config = current();
        if (!config.isEnabled()) {
            return "秒杀活动已暂停";
        }
        if (!inCutRange(userId, config.getCutRange())) {
            return "活动灰度中，暂未开放";
        }
        return null;
    }

    /** 读取当前配置（本地缓存未命中时回源 Redis）。 */
    public SeckillSwitch current() {
        return localCache.get(CACHE_KEY, key -> loadFromRedis());
    }

    /** 更新配置并使本实例缓存立即失效（其他实例最多 5 秒后生效）。 */
    public void update(boolean enabled, int cutRange) {
        if (cutRange < 0 || cutRange > FULL_CUT_RANGE) {
            throw new IllegalArgumentException("cutRange 必须在 0~100 之间：" + cutRange);
        }
        stringRedisTemplate.opsForHash().put(RedisConstants.SECKILL_DYNAMIC_CONFIG_KEY, FIELD_ENABLED, enabled ? "1" : "0");
        stringRedisTemplate.opsForHash().put(RedisConstants.SECKILL_DYNAMIC_CONFIG_KEY, FIELD_CUT_RANGE, String.valueOf(cutRange));
        localCache.invalidateAll();
    }

    /** 稳定哈希切量：按 userId 映射到 [0, 100)，小于 cutRange 的用户放行。 */
    static boolean inCutRange(Long userId, int cutRange) {
        if (cutRange >= FULL_CUT_RANGE) {
            return true;
        }
        if (cutRange <= 0) {
            return false;
        }
        return Math.floorMod(userId, FULL_CUT_RANGE) < cutRange;
    }

    private SeckillSwitch loadFromRedis() {
        Map<Object, Object> fields = stringRedisTemplate.opsForHash().entries(RedisConstants.SECKILL_DYNAMIC_CONFIG_KEY);
        if (fields.isEmpty()) {
            // 未配置过：保持默认行为（开放、100% 放量）
            return new SeckillSwitch(true, FULL_CUT_RANGE);
        }
        boolean enabled = !"0".equals(String.valueOf(fields.get(FIELD_ENABLED)));
        return new SeckillSwitch(enabled, parseCutRange(fields.get(FIELD_CUT_RANGE)));
    }

    private static int parseCutRange(Object value) {
        if (value == null) {
            return FULL_CUT_RANGE;
        }
        try {
            int parsed = Integer.parseInt(value.toString());
            if (parsed < 0) {
                return 0;
            }
            return Math.min(parsed, FULL_CUT_RANGE);
        } catch (NumberFormatException e) {
            return FULL_CUT_RANGE;
        }
    }

    /** 秒杀开关配置快照。 */
    @Data
    public static class SeckillSwitch {
        private final boolean enabled;
        private final int cutRange;
    }
}
