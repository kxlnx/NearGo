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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
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
@Slf4j
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
        // 用 Caffeine#get(key, loader) 而不是 getIfPresent + 自己兜底：
        // get(key, loader) 是「单飞」语义 —— 同一个 shopId 的并发请求只会有一个线程执行 loader，
        // 其余线程等它的结果。原来的写法里，缓存失效瞬间每个线程都会各自回源数据库（缓存击穿）。
        List<Voucher> vouchers = localVoucherCache.get(shopId, this::loadVoucherList);
        return Result.ok(vouchers);
    }

    /**
     * 券列表加载：L2 Redis → L3 MySQL（单飞，由 Caffeine 保证同一 key 只执行一次）。
     *
     * <p>注意返回空集合而不是 null：Caffeine 不会缓存 null，
     * 返回 null 会让每个并发请求都重新执行一次加载，等于没缓存。
     */
    private List<Voucher> loadVoucherList(Long shopId) {
        String redisKey = RedisConstants.CACHE_VOUCHER_LIST_KEY + shopId;

        String cached;
        try {
            cached = stringRedisTemplate.opsForValue().get(redisKey);
        } catch (Exception e) {
            // Redis 不可用：降级直查数据库，不让缓存层故障传染成业务故障
            log.warn("[缓存降级] 券列表 Redis 读取失败，直接回源数据库, shopId={}", shopId, e);
            return nullSafeList(getBaseMapper().queryVoucherOfShop(shopId));
        }
        if (cached != null && !cached.isEmpty()) {
            return JSONUtil.toList(cached, Voucher.class);
        }

        List<Voucher> vouchers = nullSafeList(getBaseMapper().queryVoucherOfShop(shopId));
        try {
            stringRedisTemplate.opsForValue().set(redisKey, JSONUtil.toJsonStr(vouchers),
                    RedisConstants.CACHE_VOUCHER_LIST_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[缓存] 券列表回填 Redis 失败（不影响本次返回）, shopId={}", shopId, e);
        }
        return vouchers;
    }

    private static List<Voucher> nullSafeList(List<Voucher> list) {
        return list == null ? new ArrayList<>() : list;
    }

    /**
     * 秒杀优惠券详情页：Caffeine 5s（L1）→ Redis 30s（L2）→ MySQL（L3）。
     * 详情数据几乎不变，用短 TTL 收敛一致性（不做跨实例失效广播）；
     * 库存是高频变化字段，不进缓存，每次实时读 seckill:stock:{voucherId}。
     */
    @Override
    public Result querySeckillVoucherDetail(Long voucherId) {
        // 同样走单飞：缓存失效瞬间不会有一批线程同时回源数据库
        SeckillVoucherDetailDTO dto = localSeckillDetailCache.get(voucherId, this::loadSeckillDetail);
        if (dto == null) {
            return Result.fail("优惠券不存在");
        }
        fillLiveStock(dto);
        return Result.ok(dto);
    }

    /**
     * 详情加载：L2 Redis → L3 MySQL。
     *
     * <p>返回 null 表示「确认不存在」：Caffeine 不缓存 null，但 L2 已写入 2 秒负缓存，
     * 因此不会反复穿透到数据库（每个请求只多一次 Redis 读）。
     */
    private SeckillVoucherDetailDTO loadSeckillDetail(Long voucherId) {
        String redisKey = RedisConstants.CACHE_SECKILL_VOUCHER_KEY + voucherId;

        String cached;
        try {
            cached = stringRedisTemplate.opsForValue().get(redisKey);
        } catch (Exception e) {
            log.warn("[缓存降级] 券详情 Redis 读取失败，直接回源数据库, voucherId={}", voucherId, e);
            return loadSeckillDetailFromDb(voucherId, null);
        }
        if (cached != null) {
            // 命中空值缓存（负缓存）：不存在的 ID 不再穿透到数据库
            if (cached.isEmpty()) {
                return null;
            }
            return JSONUtil.toBean(cached, SeckillVoucherDetailDTO.class);
        }
        return loadSeckillDetailFromDb(voucherId, redisKey);
    }

    /**
     * 回源 MySQL，组装券信息 + 秒杀信息，并回填 L2
     *
     * @param redisKey 为 null 表示 Redis 当前不可用，跳过回填（仅返回数据）
     */
    private SeckillVoucherDetailDTO loadSeckillDetailFromDb(Long voucherId, String redisKey) {
        Voucher voucher = getById(voucherId);
        if (voucher == null) {
            // 缓存空值（负缓存）：查不到的 ID 写短 TTL 空值，降低缓存穿透风险
            writeNegativeCache(redisKey);
            return null;
        }
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            log.debug("[缓存] 券 {} 不是秒杀券，不写缓存", voucherId);
            return null;
        }
        SeckillVoucherDetailDTO dto = new SeckillVoucherDetailDTO();
        BeanUtil.copyProperties(voucher, dto);
        BeanUtil.copyProperties(seckillVoucher, dto);

        if (redisKey != null) {
            try {
                stringRedisTemplate.opsForValue().set(redisKey, JSONUtil.toJsonStr(dto),
                        RedisConstants.CACHE_SECKILL_VOUCHER_TTL, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[缓存] 券详情回填 Redis 失败（不影响本次返回）, voucherId={}", voucherId, e);
            }
        }
        return dto;
    }

    private void writeNegativeCache(String redisKey) {
        if (redisKey == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue()
                    .set(redisKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[缓存] 写入负缓存失败, key={}", redisKey, e);
        }
    }

    /**
     * 库存不进缓存：每次从 Redis 资格库存实时读取，读不到就保留 MySQL 快照。
     *
     * <p>这里必须留痕：静默降级会造成「详情页显示有货、点进秒杀却提示库存未初始化」
     * 这种自相矛盾的用户体验（Redis 重启丢 key 时就会发生），排障时也没有线索。
     */
    private void fillLiveStock(SeckillVoucherDetailDTO dto) {
        String stockKey = SECKILL_STOCK_KEY + dto.getId();

        String liveStock;
        try {
            liveStock = stringRedisTemplate.opsForValue().get(stockKey);
        } catch (Exception e) {
            log.warn("[库存] 读取 Redis 实时库存失败，本次返回数据库快照, voucherId={}", dto.getId(), e);
            return;
        }
        if (liveStock != null) {
            dto.setStock(Integer.valueOf(liveStock));
            return;
        }
        log.warn("[库存] Redis 实时库存缺失，降级返回数据库快照, voucherId={}, snapshotStock={}",
                dto.getId(), dto.getStock());
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
        //两级缓存一起失效：本地 Caffeine + Redis
        localVoucherCache.invalidate(voucher.getShopId());
        stringRedisTemplate.delete(RedisConstants.CACHE_VOUCHER_LIST_KEY + voucher.getShopId());
    }
}
