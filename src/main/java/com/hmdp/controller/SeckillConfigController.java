package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.utils.SeckillSwitchManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 秒杀动态开关管理：查询公开，修改需要管理令牌。
 * 令牌校验是演示级鉴权，生产环境应接入统一权限体系并记录操作审计。
 */
@RestController
@RequestMapping("/seckill/config")
@Slf4j
public class SeckillConfigController {

    @Resource
    private SeckillSwitchManager seckillSwitchManager;

    @Value("${seckill.dynamic.admin-token:neargo-admin}")
    private String adminToken;

    /** 查询当前秒杀开关与切量配置。 */
    @GetMapping
    public Result query() {
        return Result.ok(seckillSwitchManager.current());
    }

    /** 更新秒杀开关与切量配置，使配置最多 5 秒内在各实例生效。 */
    @PutMapping
    public Result update(@RequestParam boolean enabled,
                         @RequestParam(defaultValue = "100") int cutRange,
                         @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (token == null || !token.equals(adminToken)) {
            return Result.fail("无权限操作秒杀开关");
        }
        try {
            seckillSwitchManager.update(enabled, cutRange);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        log.warn("秒杀动态开关已更新 enabled={}, cutRange={}", enabled, cutRange);
        return Result.ok(seckillSwitchManager.current());
    }
}
