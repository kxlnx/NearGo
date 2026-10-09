package com.hmdp.utils;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 秒杀开关单测：灰度切量边界、放量稳定性、本地缓存失效后重新回源。
 */
class SeckillSwitchManagerTest {

    @Test
    void fullCutRangeAllowsEveryone() {
        assertTrue(SeckillSwitchManager.inCutRange(1L, 100));
        assertTrue(SeckillSwitchManager.inCutRange(Long.MAX_VALUE, 100));
    }

    @Test
    void zeroCutRangeAllowsNobody() {
        assertFalse(SeckillSwitchManager.inCutRange(1L, 0));
    }

    @Test
    void halfCutRangeIsStableAndBounded() {
        int allowed = 0;
        for (long userId = 1; userId <= 100; userId++) {
            if (SeckillSwitchManager.inCutRange(userId, 50)) {
                allowed++;
            }
        }
        assertEquals(50, allowed);
        // 同一用户重复判断结果稳定
        assertEquals(SeckillSwitchManager.inCutRange(123L, 30),
                SeckillSwitchManager.inCutRange(123L, 30));
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidateLocalCacheForcesReloadFromRedis() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        Mockito.when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        Map<Object, Object> fields = new HashMap<>();
        fields.put("enabled", "0");
        fields.put("cutRange", "100");
        Mockito.when(hashOperations.entries(RedisConstants.SECKILL_DYNAMIC_CONFIG_KEY)).thenReturn(fields);

        SeckillSwitchManager manager = new SeckillSwitchManager();
        ReflectionTestUtils.setField(manager, "stringRedisTemplate", redisTemplate);

        // 首次读取回源 Redis
        assertFalse(manager.current().isEnabled());
        // 再读一次命中本地缓存，不再访问 Redis
        assertFalse(manager.current().isEnabled());
        Mockito.verify(hashOperations, Mockito.times(1)).entries(RedisConstants.SECKILL_DYNAMIC_CONFIG_KEY);

        // 模拟收到变更推送：失效缓存后，下一次读取重新回源 Redis
        manager.invalidateLocalCache();
        manager.current();
        Mockito.verify(hashOperations, Mockito.times(2)).entries(RedisConstants.SECKILL_DYNAMIC_CONFIG_KEY);
    }
}
