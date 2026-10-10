package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * redis工具
 *
 * @author CHEN
 * @date 2022/10/08
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    /** 重建令牌前缀：从缓存 key 派生，天然做到「不同缓存不互抢令牌」 */
    private static final String REBUILD_LOCK_PREFIX = "lock:rebuild:";
    /** 有界等待轮数与间隔：没抢到令牌时最多等 3×20ms，超时就自己回源（有上限，不会雪崩） */
    private static final int REBUILD_WAIT_RETRY = 3;
    private static final long REBUILD_WAIT_INTERVAL_MS = 20L;

    /**
     * 释放令牌脚本：把「比对持有者 + 删除」合成一条原子命令。
     * <p>若拆成 get 再 delete 两条命令，两步之间令牌可能刚好过期并被别人抢到，
     * 就会误删别人的令牌。用 Lua 才能真正做到「只删自己的」。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Autowired
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将任意对象序列化成json存入redis
     *
     * @param key   关键
     * @param value 价值
     * @param time  时间
     * @param unit  单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 将任意对象序列化成json存入redis 并且携带逻辑过期时间
     *
     * @param key   关键
     * @param value 价值
     * @param time  时间
     * @param unit  单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //存入redis
        // 逻辑过期判断仍靠 value 里的 expireTime；这里再给 Key 一个物理 TTL 兜底，
        // 防止长期无人访问的 Key 永不过期、无界占用内存（物理过期后走"未命中回填"路径）
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData),
                LOGICAL_EXPIRE_FALLBACK_TTL, TimeUnit.HOURS);
    }

    /**
     * 设置空值解决缓存穿透
     *
     * @param keyPrefix  关键前缀
     * @param id         id
     * @param type       类型
     * @param dbFallback db回退
     * @param time       时间
     * @param unit       单位
     * @return {@link R}
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix
            , ID id
            , Class<R> type
            , Function<ID, R> dbFallback
            , Long time
            , TimeUnit unit) {
        String key = keyPrefix + id;
        //从redis中查询
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StringUtils.isNotEmpty(json)) {
            //存在直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断空值
        if ("".equals(json)) {
            return null;
        }
        //不存在 查询数据库
        R r = dbFallback.apply(id);
        if (r == null) {
            //redis写入空值
            this.set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
            //数据库不存在 返回错误
            return null;
        }
        //数据库存在 写入redis
        this.set(key, r, time, unit);
        //返回
        return r;
    }

    /**
     * 逻辑过期解决缓存击穿。
     *
     * <p>本方法承担三件事：
     * <ol>
     *   <li><b>逻辑过期</b>：key 物理不过期（24h 兜底），靠 value 里的 expireTime 判断，
     *       过期后返回旧数据并由一个线程异步重建，读请求永不阻塞；</li>
     *   <li><b>重建令牌</b>：跨实例只放一个实例回源数据库，令牌带 UUID 持有者标识，
     *       释放走 Lua 原子「比对+删除」，不会误删别人的令牌，持有者崩溃由 TTL 自动释放；</li>
     *   <li><b>降级</b>：Redis 不可用时直接回源数据库，不让缓存层故障传染成业务故障；
     *       没抢到令牌的请求做「有界等待」，绝不死等或自旋。</li>
     * </ol>
     *
     * <p>调用方若再叠一层进程内单飞（如 Caffeine#get(key, loader)），
     * 可把「本实例内」的并发也合并成一次加载，两层合起来全局最多一次回源。
     *
     * @param keyPrefix 缓存 key 前缀
     * @param id        业务 id
     * @param type      返回类型
     * @param dbFallback 回源数据库的方法
     * @param time      逻辑过期时间
     * @param unit      时间单位
     * @return 缓存或数据库中的数据；不存在返回 null
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix
            , ID id
            , Class<R> type
            , Function<ID, R> dbFallback
            , Long time
            , TimeUnit unit) {
        String key = keyPrefix + id;
        String lockKey = REBUILD_LOCK_PREFIX + key;

        //从redis中查询（Redis 故障时降级直查数据库）
        String json;
        try {
            json = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("[缓存降级] Redis 读取失败，直接回源数据库, key={}", key, e);
            return dbFallback.apply(id);
        }
        //命中空值缓存：数据库确认过这个 ID 不存在，直接返回（防穿透）
        if (json != null && json.isEmpty()) {
            return null;
        }
        //Key 完全不存在：缓存未预热（或负缓存已过期、或 Redis 丢数据）
        if (json == null) {
            String token = tryLock(lockKey);
            if (token != null) {
                try {
                    //抢到重建令牌：自己回源并回填
                    return rebuild(key, id, type, dbFallback, time, unit);
                } finally {
                    unLock(lockKey, token);
                }
            }
            //没抢到：别的实例正在回源，做有界等待（最多 3×20ms），避免一起压库
            for (int i = 0; i < REBUILD_WAIT_RETRY; i++) {
                sleepQuietly(REBUILD_WAIT_INTERVAL_MS);
                String again = safeGet(key);
                if (again != null) {
                    return again.isEmpty() ? null : parse(again, type);
                }
            }
            //等待超时：自己回源（此时最多只有少量请求走到这里，不会形成雪崩）
            log.warn("[缓存] 等待重建超时，回退为自行回源, key={}", key);
            return rebuild(key, id, type, dbFallback, time, unit);
        }
        //命中 反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = parse(json, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期 直接返回
            return r;
        }
        //已过期：抢到令牌的实例异步重建，其余实例立刻返回旧数据（不阻塞用户）
        String token = tryLock(lockKey);
        if (token != null) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    rebuild(key, id, type, dbFallback, time, unit);
                } catch (Exception e) {
                    log.error("[缓存] 异步重建失败, key={}", key, e);
                } finally {
                    unLock(lockKey, token);
                }
            });
        }
        //返回过期数据（旧值），保证读请求永不阻塞
        return r;
    }

    /**
     * 反序列化缓存内容
     */
    @SuppressWarnings("unchecked")
    private <R> R parse(String json, Class<R> type) {
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        return BeanUtil.toBean(jsonObject, type);
    }

    /**
     * 回源数据库并写回缓存（含负缓存），供同步/异步重建复用
     */
    private <R, ID> R rebuild(String key, ID id, Class<R> type,
                              Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        R dbResult = dbFallback.apply(id);
        if (dbResult == null) {
            //数据库也没有：写空值缓存（短 TTL），防止同一个不存在的 ID 反复穿透
            this.set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
            return null;
        }
        //数据库有：按逻辑过期格式写入，下次请求直接走逻辑过期流程
        this.setWithLogicalExpire(key, dbResult, time, unit);
        return dbResult;
    }

    /**
     * 读取缓存，Redis 异常时返回 null（由调用方决定降级策略）
     */
    private String safeGet(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("[缓存降级] Redis 读取失败, key={}", key, e);
            return null;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 简易线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 获取重建令牌（不是锁：拿不到不排队、不等待，调用方直接走降级路径）。
     *
     * <p>令牌值带 UUID 持有者标识，释放时必须校验持有者；TTL 到期自动释放，
     * 因此持有者崩溃也不会死锁。缓存重建是幂等操作，即便令牌提前过期导致
     * 少量重复重建，后果也只是多查几次库，不会产生数据错误。
     *
     * @param lockKey 令牌 key
     * @return 抢到返回令牌值；没抢到或 Redis 异常返回 null
     */
    private String tryLock(String lockKey) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean flag = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, token, LOCK_SHOP_TTL, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(flag) ? token : null;
        } catch (Exception e) {
            log.warn("[缓存降级] 获取重建令牌失败，本次不参与重建, lockKey={}", lockKey, e);
            return null;
        }
    }

    /**
     * 释放令牌：只删自己的（Lua 原子比对+删除）
     *
     * @param lockKey 令牌 key
     * @param token   持有者标识
     */
    private void unLock(String lockKey, String token) {
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
        } catch (Exception e) {
            //释放失败不影响正确性：令牌会在 LOCK_SHOP_TTL 秒后自动过期
            log.warn("[缓存] 释放重建令牌失败（将由 TTL 自动过期兜底）, lockKey={}", lockKey, e);
        }
    }
}
