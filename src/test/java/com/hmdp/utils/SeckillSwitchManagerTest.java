package com.hmdp.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灰度切量单测：边界值、放量比例与稳定性。
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
}
