package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillVoucherDetailDTO;
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

    /** 秒杀券详情页本地缓存：TTL 5 秒，抗“秒杀详情”这个极热 Key。 */
    private final Cache<Long, SeckillVoucherDetailDTO> localSeckillDetailCache = Caffeine.newBuilder()
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

    /**
     * 秒杀优惠券详情页：Caffeine 5s（L1）→ Redis 30s（L2）→ MySQL（L3）。
     * 详情数据几乎不变，用短 TTL 收敛一致性（不做跨实例失效广播）；
     * 库存是高频变化字段，不进缓存，每次实时读 seckill:stock:{voucherId}。
     */
    @Override
    public Result querySeckillVoucherDetail(Long voucherId) {
        // L1：进程内本地缓存，命中直接返回，不走网络
        SeckillVoucherDetailDTO local = localSeckillDetailCache.getIfPresent(voucherId);
        if (local != null) {
            fillLiveStock(local);
            return Result.ok(local);
        }

        // L2：Redis 缓存，命中后回填 L1
        String redisKey = RedisConstants.CACHE_SECKILL_VOUCHER_KEY + voucherId;
        String cached = stringRedisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            if (cached.isEmpty()) {
                return Result.fail("优惠券不存在");   // 命中空值缓存（负缓存）：不存在的 ID 不再穿透到数据库
            }
            SeckillVoucherDetailDTO dto = JSONUtil.toBean(cached, SeckillVoucherDetailDTO.class);
            localSeckillDetailCache.put(voucherId, dto);
            fillLiveStock(dto);
            return Result.ok(dto);
        }

        // L3：回源 MySQL，组装券信息 + 秒杀信息
        Voucher voucher = getById(voucherId);
        if (voucher == null) {
            // 缓存空值（负缓存）：查不到的 ID 写短 TTL 空值，降低缓存穿透风险
            stringRedisTemplate.opsForValue().set(redisKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.SECONDS);
            return Result.fail("优惠券不存在");
        }
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("该优惠券不是秒杀券");
        }
        SeckillVoucherDetailDTO dto = new SeckillVoucherDetailDTO();
        BeanUtil.copyProperties(voucher, dto);
        BeanUtil.copyProperties(seckillVoucher, dto);

        // 回填 L2 + L1，下一个请求直接命中本地内存
        stringRedisTemplate.opsForValue().set(
                redisKey,
                JSONUtil.toJsonStr(dto),
                RedisConstants.CACHE_SECKILL_VOUCHER_TTL,
                TimeUnit.SECONDS);
        localSeckillDetailCache.put(voucherId, dto);
        fillLiveStock(dto);
        return Result.ok(dto);
    }

    /** 库存不进缓存：每次从 Redis 资格库存实时读取，读不到就保留 MySQL 快照。 */
    private void fillLiveStock(SeckillVoucherDetailDTO dto) {
        String liveStock = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + dto.getId());
        if (liveStock != null) {
            dto.setStock(Integer.valueOf(liveStock));
        }
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
