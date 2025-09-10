package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 本地热点券列表缓存，TTL 5 秒，降低单热点 Key 对 Redis 的访问压力。 */
    private final Cache<Long, List<Voucher>> localVoucherCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .build();

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        List<Voucher> local = localVoucherCache.getIfPresent(shopId);
        if (local != null) {
            return Result.ok(local);
        }

        String redisKey = RedisConstants.CACHE_VOUCHER_LIST_KEY + shopId;
        String cached = stringRedisTemplate.opsForValue().get(redisKey);
        if (cached != null && !cached.isEmpty()) {
            List<Voucher> vouchers = JSONUtil.toList(cached, Voucher.class);
            localVoucherCache.put(shopId, vouchers);
            return Result.ok(vouchers);
        }

        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        stringRedisTemplate.opsForValue().set(
                redisKey,
                JSONUtil.toJsonStr(vouchers),
                RedisConstants.CACHE_VOUCHER_LIST_TTL,
                TimeUnit.SECONDS
        );
        localVoucherCache.put(shopId, vouchers);
        return Result.ok(vouchers);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        //保存秒杀库存的到redis
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY+voucher.getId(),voucher.getStock().toString());
        localVoucherCache.invalidate(voucher.getShopId());
        stringRedisTemplate.delete(RedisConstants.CACHE_VOUCHER_LIST_KEY + voucher.getShopId());
    }
}
