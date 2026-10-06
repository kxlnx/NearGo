package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    /** 逻辑过期 Key 的物理 TTL 兜底（小时）：防止长期无人访问的 Key 永不过期、无界占用内存。 */
    public static final Long LOGICAL_EXPIRE_FALLBACK_TTL = 24L;

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;
    /** 用户维度并发锁：防止同一用户的多条订单消息并发穿过幂等校验。 */
    public static final String LOCK_ORDER_KEY = "lock:order:";

    public static final String CACHE_TYPE_KEY = "cache:type";
    public static final String CACHE_VOUCHER_LIST_KEY = "cache:voucher:list:";
    public static final Long CACHE_VOUCHER_LIST_TTL = 30L;

    public static final String CACHE_SECKILL_VOUCHER_KEY = "cache:seckill:voucher:";
    public static final Long CACHE_SECKILL_VOUCHER_TTL = 30L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    /** Redis Hash：记录一次 Redis 预扣对应的订单、用户和预扣时间，供补偿任务使用。 */
    public static final String SECKILL_RESERVATION_KEY = "seckill:reservation:";
    /** Redis Hash：秒杀动态开关与灰度切量配置（字段：enabled / cutRange），供运营动态调整。 */
    public static final String SECKILL_DYNAMIC_CONFIG_KEY = "config:seckill:dynamic";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
