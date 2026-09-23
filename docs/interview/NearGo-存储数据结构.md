# NearGo 七大问题对应的存储数据结构

本页只解释《NearGo-简历六大问题.md》正文当前列出的七个问题涉及的数据。文件名沿用旧名，正文实际是七大问题。这里列的是源码中的结构；带“示例”的值用于说明格式，不代表当前 Redis 中一定存在，也不代表 hmdp.sql 已有该秒杀券或订单。

占位符先看懂：{shopId} 是店铺 ID，{voucherId} 是优惠券 ID，{userId} 是用户 ID，{orderId} 是订单 ID。例如 cache:shop:1 里的 1 是店铺 ID；seckill:reservation:1001:2 里的 1001 是券 ID、2 是用户 ID。花括号只是文档写法，实际 Key 不含花括号。

## 对应总表

| 问题 | Redis / Caffeine | MySQL |
| --- | --- | --- |
| 一、库存一致性治理 | 库存 String、用户 Set、预扣 Hash | tb_seckill_voucher、tb_voucher_order；券基本信息来自 tb_voucher |
| 二、异步削峰与消息幂等 | 复用问题一的三种数据；订单 ID 计数器；Redisson 用户锁 | tb_voucher_order、tb_seckill_voucher |
| 三、缓存可靠性治理 | 店铺详情 String、重建锁 String；空值缓存仅在备用的透传方法中实现 | tb_shop |
| 四、订单状态并发控制 | 复用问题一的库存、用户 Set、预扣 Hash，关单时释放 | tb_voucher_order.status、tb_seckill_voucher.stock |
| 五、故障补偿与最终一致性 | 扫描预扣 Hash 和用户 Set，必要时修复库存 String | tb_voucher_order；XXL-JOB Admin 自有调度库另算 |
| 六、访问流量治理 | 两类限流 ZSet：用户维度、IP 维度 | 无直接业务表 |
| 七、本地缓存与两级缓存治理 | 两个 Caffeine 缓存、两个 Redis JSON 缓存；详情读取秒杀库存 String | tb_voucher、tb_seckill_voucher |

## 一、库存一致性治理

这里卖的是“秒杀优惠券”。假设为了理解结构，券 ID 为 1001，初始库存 100，用户 ID 为 2；这些是示例 ID，hmdp.sql 并没有预置券 1001。

| Key 模板 → 示例 | 类型 | Value 的结构和含义 |
| --- | --- | --- |
| seckill:stock:{voucherId} → seckill:stock:1001 | String | 数字字符串，例如 "99"，表示券 1001 的 Redis 剩余资格库存 |
| seckill:order:{voucherId} → seckill:order:1001 | Set | 用户 ID 集合，例如 {"2"}，表示用户 2 已获得该券的下单资格 |
| seckill:reservation:{voucherId}:{userId} → seckill:reservation:1001:2 | Hash | 这次预扣的订单 ID、用户、券、时间和重投信息 |

预扣 Hash 的字段示例：

    Key: seckill:reservation:1001:2
    orderId     -> "20001"       本次预扣对应的订单 ID
    userId      -> "2"           用户 ID
    voucherId   -> "1001"        优惠券 ID
    reservedAt  -> "1789890000"  Redis 服务器预扣时间，秒级时间戳
    retryCount  -> "0"           对账重投次数
    lastRetryAt -> "0"           最近重投时间，秒级时间戳；0 表示尚未重投
    TTL: 604800 秒，即 7 天

预扣成功时，seckill.lua 在同一脚本中执行库存 -1、SADD 用户、HSET 流水。返回值：0=成功，1=库存不足，2=该用户已在 Set 中，3=库存 Key 尚未初始化。只有 0 才继续发 RocketMQ。Redis 库存和用户 Set 没有在这些写入处设置 TTL；预扣 Hash 有 7 天 TTL。

MySQL 侧：tb_voucher.id 是券的基本信息主键；tb_seckill_voucher.voucher_id 对应这张券，stock 是数据库库存；真正的订单写在 tb_voucher_order，关键列为 id、user_id、voucher_id、status。消费端执行“voucher_id 匹配且 stock > 0”才将 tb_seckill_voucher.stock 减 1，再保存订单。Redis Lua 的原子性不覆盖 MySQL。

## 二、异步削峰与消息幂等

这一点不另造库存结构：问题一的 seckill:stock:1001、seckill:order:1001、seckill:reservation:1001:2 仍在使用。RocketMQ 消息不是 Redis Key，也不是 MySQL 表；消息体携带 id、userId、voucherId，对应 tb_voucher_order 的订单 ID、用户 ID、券 ID。

另有两个配套 Redis 数据：

| Key 模板 → 示例 | 类型 | 含义 |
| --- | --- | --- |
| icr:order:{yyyyMMdd} → icr:order:20260922 | String | 当日订单 ID 自增序列，例如 "18"；RedisIdWorker 将序列与时间部分组合成订单 ID |
| lock:order:{userId} → lock:order:2 | Redisson 管理的锁数据 | 订单消费者按用户 ID 获取 RLock；不把内部 Value 当作业务 String 解释 |

消费者先按 tb_voucher_order.id 查重，再在用户锁内按 user_id + voucher_id 检查未取消订单；无重复时条件扣减 tb_seckill_voucher.stock 并插入 tb_voucher_order。数据库表目前只有订单 id 主键，没有 (user_id, voucher_id) 联合唯一索引，所以不能说“一人一单由数据库唯一索引保证”。已取消的 status=4 订单不算当前重复订单。

## 三、缓存可靠性治理

| Key 模板 → 示例 | 类型 | Value / 作用 |
| --- | --- | --- |
| cache:shop:{shopId} → cache:shop:1 | String，内容为 RedisData JSON | data 是 tb_shop 对应的店铺对象；expireTime 是逻辑过期时间 |
| lock:shop:{shopId} → lock:shop:1 | String | 固定值 "1"，SET NX 并设置 10 秒 TTL；只让一个线程重建店铺缓存 |

cache:shop:1 的示意结构：

    {
      "data": {"id": 1, "name": "103茶餐厅", "typeId": 1},
      "expireTime": "2026-09-22T10:30:00"
    }

这里的 1 是店铺 ID。data 中示例店铺名称来自 hmdp.sql 的 tb_shop 第 1 行，expireTime 是说明格式的示例时间。当前 /shop/{id} 调用的是 queryWithLogicalExpire：Redis Key 不存在时直接返回空、不回源 MySQL；Key 存在但逻辑过期时先返回旧店铺，再由抢到锁的线程异步查 tb_shop 并重建。该逻辑过期 Key 写入时没有物理 TTL。

CacheClient 还实现了 queryWithPassThrough：数据库查不到时在同一个 cache:shop:{shopId} 下存空字符串并设短 TTL，用于防穿透。但当前 /shop/{id} 没有调用这个方法，不能说空值缓存与逻辑过期在该接口同时生效。更新店铺会修改 tb_shop 并删除对应缓存 Key；之后若没有重新预热，逻辑过期查询遇到 Key 不存在仍直接返回空。

## 四、订单状态并发控制

核心数据是 tb_voucher_order 的一行。示例结构：

    id=20001, user_id=2, voucher_id=1001, status=1

status 在当前链路里：1=未支付、2=已支付、4=已取消。payCallback 只允许 WHERE id=? AND status=1 更新成 2；超时关单只允许同样旧状态更新成 4。谁先成功修改，另一方就影响 0 行。支付时间保存在 pay_time。

关单从 1 改成 4 成功后，tb_seckill_voucher.stock +1；再执行 seckill_rollback.lua，针对问题一的三个 Redis Key：

    seckill:stock:1001          库存 +1
    seckill:order:1001          SREM 移除用户 2
    seckill:reservation:1001:2  删除预扣流水

脚本通过用户是否还在 Set 中避免重复加 Redis 库存。这里的状态条件更新体现乐观并发控制思想，不是额外建了一张“乐观锁表”。

## 五、故障补偿与最终一致性

XXL-JOB 执行器扫描问题一写入的 seckill:reservation:* Hash；例如 seckill:reservation:1001:2，从中取出 orderId=20001，再按这个 ID 查 tb_voucher_order。

| MySQL 查询结果 | 对 Redis / MQ 的动作 |
| --- | --- |
| 没有该订单 | 满足等待与重投间隔后，使用流水里的原订单 ID 重投 RocketMQ；随后更新 Hash 的 retryCount、lastRetryAt |
| 订单 status=4 | 执行 seckill_rollback.lua，幂等释放库存、用户资格和流水 |
| 订单存在且未取消 | SADD 补回 seckill:order:1001 中的用户 2，删除已收敛的预扣 Hash |

数量对账复用 seckill:order:{voucherId}：SCARD 得到 Redis 用户数，和 MySQL 中同券且 status != 4 的 tb_voucher_order 行数比较。不等时当前代码打警告日志，不能写成“自动按差额改库存”。XXL-JOB Admin 的任务配置与执行日志存在它自己的调度数据库，不是 NearGo 的 tb_voucher_order。

注意：预扣 Hash 只有 7 天 TTL，因此这条扫描补偿链路依赖记录仍在有效期内；已经过期的流水不能再由该任务扫描出来。

## 六、访问流量治理

当前注解实际应用在秒杀接口和 /shop/{id} 店铺详情接口。每个限流对象对应一个 Redis ZSet：

| Key 模板 → 示例 | 限流对象 | ZSet 的 member / score |
| --- | --- | --- |
| seckill:user:{userId} → seckill:user:2 | 用户 2 的秒杀请求 | member 是“毫秒时间戳-随机数”，score 是该请求的毫秒时间戳 |
| shop:query:ip:{ip} → shop:query:ip:127.0.0.1 | 这个 IP 的店铺详情请求 | 与上面相同，每个成员对应一次请求 |

示例内容：

    Key: seckill:user:2
    member="1789890000123-123456", score=1789890000123
    member="1789890000456-654321", score=1789890000456

Lua 删除时间窗口外的成员、用 ZCARD 计数、未超限才 ZADD 当前请求。通过则给 ZSet 设置与窗口相同的 TTL。这里保存的是“最近请求记录”，不是订单、用户资料或优惠券库存；不直接对应 MySQL 表。

## 七、本地缓存与两级缓存治理

问题七有两条读取路径，不要和问题三的店铺详情缓存混成一个 Key。

| 查询 | Caffeine 一级缓存，JVM 对象 | Redis 二级缓存，String JSON | MySQL 回源 |
| --- | --- | --- | --- |
| 店铺优惠券列表 | shopId=1L → List<Voucher> | cache:voucher:list:{shopId} → cache:voucher:list:1，JSON 数组 | tb_voucher LEFT JOIN tb_seckill_voucher，按 shop_id 查询上架券 |
| 秒杀券详情 | voucherId=1001L → SeckillVoucherDetailDTO | cache:seckill:voucher:{voucherId} → cache:seckill:voucher:1001，DTO JSON 对象 | 分别按 ID 查 tb_voucher 和 tb_seckill_voucher |

例如店铺券列表在 Caffeine 中是 Java 对象列表，在 Redis 中则是序列化字符串：

    Caffeine: 1L → [Voucher(id=1, shopId=1, title="50元代金券", ...)]
    Redis:    cache:voucher:list:1 → [{"id":1,"shopId":1,"title":"50元代金券",...}]

hmdp.sql 预置的券 id=1 是普通券（type=0），可以用来说明店铺列表结构；上面 voucherId=1001 的秒杀详情仍是示例，不是预置数据。两种 Caffeine 缓存都是最多 1000 项、写入后 5 秒过期；两种 Redis 缓存都是写入后 30 秒过期。

秒杀券详情 DTO 含 stock 字段：从 MySQL 构造 DTO 后，会把包含库存快照的 DTO 写入 Redis 和 Caffeine；每次返回前 fillLiveStock 再读取 seckill:stock:{voucherId}，读到则用 Redis 实时值覆盖 DTO.stock，读不到则保留快照。准确说法是“返回前实时覆盖库存”，不是“缓存对象完全不含库存字段”。店铺券列表的 Voucher 对象也可包含 SQL 关联查出的 stock，本代码没有对列表逐项做实时库存覆盖。

新增秒杀券时，tb_voucher 保存券本体，tb_seckill_voucher 保存库存和活动时间，同时写 seckill:stock:{voucherId}；随后失效本实例的店铺券列表 Caffeine 和对应 Redis 列表缓存。其他实例的 Caffeine 不共享，依赖 5 秒过期收敛。

## MySQL 表与七大问题的最短映射

| 表 | 关键字段 | 对应问题 |
| --- | --- | --- |
| tb_shop | id（主键）、name、type_id、address 等店铺详情字段 | 三 |
| tb_voucher | id（主键）、shop_id、title、pay_value、actual_value、type、status | 一的券身份、七的券基础信息 |
| tb_seckill_voucher | voucher_id（主键，对应券 ID）、stock、begin_time、end_time | 一、二、四、七 |
| tb_voucher_order | id（主键）、user_id、voucher_id、status、create_time、pay_time 等 | 一、二、四、五 |

问题六的限流 ZSet 没有业务表。问题五还涉及独立的 XXL-JOB Admin 调度库；它不是上述四张业务表的一部分。

源码核对入口：RedisConstants.java、seckill.lua、seckill_rollback.lua、VoucherOrderServiceImpl.java、SeckillReconciliationTask.java、CacheClient.java、ShopServiceImpl.java、VoucherServiceImpl.java、RateLimitAspect.java、sliding_window.lua、VoucherMapper.xml、hmdp.sql。
