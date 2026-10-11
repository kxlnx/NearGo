# NearGo 五大问题复盘

> 严格按简历的 **5 条**优化逐条展开，不增加额外问题。
> 每条固定结构：**简历原文 → 这条在说什么 → 完整执行步骤（带具体数据）→ 返回值/行为说明 → 核心代码 → 为什么这么选 → 边界**。
> 代码位置用 `文件:行号` 标注，与当前源码一致。
> 本文由《NearGo 七大问题复盘》按简历 5 条**重新编排**：前 4 条把原"七问"按下挂关系合并（内容一字未删），第 5 条是原七问**未覆盖**的新增章节。

**简历 5 条**

1. 库存一致性与流量治理：基于 Redis + Lua 原子完成库存校验、预扣与用户去重，结合 MySQL 条件扣库存避免超卖与重复下单；并以滑动窗口限流（IP/用户维度）抵御恶意刷取与突发流量。
2. 异步削峰与订单状态控制：引入 RocketMQ 解耦秒杀请求与订单落库，以消息幂等保证重复消费不重复建单；并以延迟消息超时关单、乐观锁控制状态流转，解决支付回调与关单的并发竞争。
3. 故障补偿与最终一致性：基于 XXL-JOB 扫描 Redis 预扣元数据与 MySQL 订单差异，对未落库订单以原订单 ID 重投、对已取消订单释放预扣，异常任务失败重试并落库补偿账本。
4. 缓存可靠性治理：以 Redis 逻辑过期 + 互斥锁解决热点店铺缓存击穿、缓存空值降低穿透风险；并以 Caffeine + Redis 两级缓存减少热点查询对 Redis 与数据库的压力。
5. 流程编排与可扩展性重构：订单消费链路重构为责任链（幂等 → 用户锁 → 用户券查重 → 扣库存 → 落库），节点以 `setNext` 显式串链、**装配期静态判环**保证不成环、执行结果枚举化；对账处置重构为规则树（按对象身份判环 + 可配置步数上限）。新增分支无需改动主流程，引擎与节点可独立单测。

---

## 与旧"七问"的对应关系（先看这张表）

| 本文 5 条 | 由旧"七问"的哪些合并而来 | 本文小节 |
| --- | --- | --- |
| 第 1 条 库存一致性与流量治理 | 旧一（库存一致性治理）＋ 旧六（访问流量治理） | 1.1、1.2 |
| 第 2 条 异步削峰与订单状态控制 | 旧二（异步削峰与消息幂等）＋ 旧四（订单状态并发控制） | 2.1、2.2 |
| 第 3 条 故障补偿与最终一致性 | 旧五（故障补偿与最终一致性）＋ **新增"补偿账本"小节** | 3.1、3.2 |
| 第 4 条 缓存可靠性治理 | 旧三（缓存可靠性治理）＋ 旧七（本地缓存与两级缓存治理） | 4.1、4.2 |
| 第 5 条 流程编排与可扩展性重构 | **旧七问未覆盖，本文新增** | 5.1、5.2、5.3 |

> **章节编号规则**：`X.Y` 中 `X` 是简历条目号，`Y` 是该条下的第几个子问题。
> 正文里所有"见 1.1 第 3 节"这类引用都按这个编号走；小节内部的"第 N 节"仍指**该小节自己**的编号（沿用原文，未改动）。
> 旧文档仍保留在 `NearGo-简历六大问题.html`，两份可以对照看。

---

## 总览：五条优化不是孤立的，是三条链路 + 一次结构重构

> **怎么读这篇**：前 4 条是**沿链路的治理**——写侧秒杀主链路（1.1 → 2.1 → 2.2 → 3.1）、读侧缓存（4.1 / 4.2）、入口限流（1.2）；
> 第 5 条是**横切的结构重构**——把 2.1 的消费链路、3.1 的对账处置链路从 if-else 改成责任链与规则树，让新增分支不必改主流程、节点可独立单测。
> 也就是说：1~4 条回答"每个问题怎么解"，第 5 条回答"这些解法怎么组织成可扩展的结构"。


五条优化围绕三个入口串成三条链路，外加一次横切的结构重构：**秒杀主链路**、**店铺查询链路**、**优惠券查询链路**；其中秒杀主链路又分「正常流程」和「异常兜底」两段。

### 共享物视角总图（五条为什么不是五个孤立题）

```mermaid
flowchart TB
    subgraph WRITE["写侧：秒杀主链路（正常 1.1→2.1→2.2，异常 3.1 兜底）"]
        P1["1.1 秒杀资格预扣<br/>seckill.lua"]
        P2["2.1 异步下单落库<br/>RocketMQ 消费端"]
        P4["2.2 状态机与超时关单"]
        P5["3.1 XXL-JOB 对账补偿"]
        P1 -->|"MQ 普通消息"| P2
        P2 -->|"MQ 延迟消息"| P4
        P5 -.->|"未落库 → 原单号重投"| P2
        P5 -.->|"已取消 → 释放预扣"| P4
        P5 -.->|"已落库 → 补回一人一单名单"| P1
    end

    subgraph READ["读侧：缓存治理（被 1.2 挡在入口）"]
        P3["4.1 店铺详情缓存<br/>逻辑过期 + 互斥锁 + 空值"]
        P7["4.2 券列表 / 秒杀详情<br/>Caffeine + Redis 两级"]
    end

    P6["1.2 限流<br/>RateLimiter + sliding_window.lua"]

    subgraph SHARED["共享物：把五条缝成一张网"]
        SK["共享 Redis Key<br/>seckill:stock / seckill:order / seckill:reservation"]
        SL["共享脚本 seckill_rollback.lua<br/>1.1 发送失败 · 2.2 关单 · 3.1 对账 三处共用"]
        ST["共享状态语义<br/>status：1 待支付 · 2 已支付 · 4 已取消"]
        SPA["共享一致性套路一<br/>Redis 资格库存 + MySQL 持久库存"]
        SPB["共享一致性套路二<br/>先改库、再删缓存"]
    end

    P6 -->|"user 维度 5 次/秒"| P1
    P6 -->|"IP 维度 20 次/秒"| P3

    P1 ---|"预扣 stock-1、SADD 名单、HSET 流水"| SK
    P4 ---|"关单 stock+1、SREM 名单"| SK
    P5 ---|"释放已取消订单的预扣"| SK
    P7 -.->|"fillLiveStock 实时读 stock"| SK

    P1 ---|"发送失败补偿"| SL
    P4 ---|"关单释放"| SL
    P5 ---|"对账释放"| SL

    P2 ---|"ne(status,4) 幂等"| ST
    P4 ---|"1→2 支付 / 1→4 关单"| ST
    P5 ---|"按状态定修复分支"| ST

    P1 ---|"资格库存 -1"| SPA
    P2 ---|"持久库存 -1"| SPA
    P4 ---|"归还 +1"| SPA
    P5 ---|"释放 +1"| SPA

    P3 ---|"update 删 cache:shop"| SPB
    P7 ---|"addSeckillVoucher 删列表缓存"| SPB
```

> 读法：实线＝主流程调用，虚线＝兜底 / 跨链路读取；中间一栏是共享物。
> 容易猜错的点：`cache:voucher:list:{shopId}`、`cache:seckill:voucher:{id}` **只属于 4.2**，其他条目里不出现；真正跨条目复用的是 `seckill:stock / seckill:order / seckill:reservation` 三件套。另外目前 `@RateLimiter` 只挂在 `VoucherOrderController#seckillVoucher`（秒杀）和 `ShopController#queryShopById`（店铺详情），优惠券接口没有挂。

### 链路一：秒杀主链路（1.2 → 1.1 → 2.1 → 2.2 → 3.1）

```mermaid
flowchart TD
    A["客户端发起秒杀"] --> B0["com.hmdp.aspect.RateLimitAspect#around 拦截<br/>@RateLimiter(key=seckill, windowSeconds=1, count=5, dimension=user)"]

    subgraph S6["1.2 限流：Redis ZSet + Lua，计数多实例共享"]
        B0 --> B2{"buildKey：key 前缀 + 维度后缀<br/>秒杀 → seckill:user:{userId}<br/>店铺 → shop:query:ip:{ip}<br/>global → 只用前缀"}
        B2 --> B3["EVAL sliding_window.lua 原子四步<br/>ZREMRANGEBYSCORE 删窗口外请求<br/>ZCARD 统计窗口内数量<br/>未达阈值 → ZADD 本次请求 + EXPIRE"]
    end
    B3 -->|"返回 1：放行"| C["VoucherOrderController#seckillVoucher"]
    B3 -->|"返回 0（超阈值）/ null（Redis 异常）"| B1["Result.fail：操作过于频繁，请稍后再试<br/>Fail Closed：宁可拒绝"]

    subgraph S1["1.1 Redis Lua 预扣 seckill.lua"]
        C --> D{"库存 Key 存在？库存大于 0？用户未下单？"}
        D -->|"不存在 → 3"| D1["失败：秒杀库存未初始化"]
        D -->|"库存不足 → 1"| D2["失败：库存不足"]
        D -->|"已下单 → 2"| D3["失败：不能重复下单"]
        D -->|"通过 → 0"| E["INCRBY 库存 -1<br/>SADD 名单<br/>HSET reservation（7 天 TTL）"]
    end

    E --> F{"syncSend orderTopic<br/>等待 Broker 确认"}
    F -->|"抛异常：明确失败"| G["seckill_rollback.lua<br/>资格在才 SREM + 库存 +1 + DEL 流水<br/>不在只清流水（幂等）"] --> G1["返回：排队失败，请重试"]
    F -->|"确认成功"| MQ["RocketMQ Broker · seckill-order-topic<br/>消息在队列中排队 = 削峰 / 缓冲"]

    MQ -->|"生产者收到 Broker 确认（ACK）"| H["接口返回 orderId<br/>（订单尚未落库，只是入队）"]
    MQ -->|"Broker 主动推送消息给消费者<br/>（至少一次投递）"| I

    subgraph S2["2.1 消费端落库（幂等 + 事务）"]
        I["SeckillOrderListener.onMessage"] --> J{"订单 ID 幂等：id 已存在？"}
        J -->|"是：重复消息"| J1["直接结束（ACK，提交 offset）"]
        J -->|"否"| K["Redisson 用户锁 lock:order:{userId}<br/>（finally 释放）"]
        K -->|"tryLock 失败"| R3["抛异常 → 事务回滚<br/>框架返回 RECONSUME_LATER"]
        K -->|"拿到锁"| L{"用户 + 券联合查重<br/>（status != 4，取消单不算）"}
        L -->|"已有订单"| L1["直接结束（ACK，提交 offset）"]
        L -->|"无"| M{"MySQL 条件扣库存<br/>stock-1 WHERE stock>0"}
        M -->|"影响 0 行（库存不一致）"| R3
        M -->|"影响 1 行"| N["INSERT 订单（与扣库存同一事务）"]
    end
    R3 -.->|"RocketMQ 消费重试：Broker 稍后重投（至少一次，重试耗尽进死信）"| I

    N -->|"落库成功才发"| O["syncSend 延迟消息<br/>order-timeout-topic · level 5 = 1 分钟"]
    O -->|"发送失败：抛异常"| R2["消费者重试：幂等跳过落库<br/>再重发延迟消息"]
    R2 -.-> I
    O -->|"发送成功：消息在 Broker 延迟队列暂存 1 分钟"| P

    subgraph S4["2.2 1 分钟后：延迟关单 / 支付竞争"]
        P["OrderTimeoutListener.onMessage(orderId)"] --> E4{"getById(orderId)<br/>订单存在？"}
        E4 -->|"不存在"| F4["return false"]
        E4 -->|"存在"| Q{"条件更新：<br/>SET status=4 WHERE id=? AND status=1"}
        Q -->|"影响 0 行<br/>（支付先赢 / 消息重复）"| Q1["return false<br/>不碰库存"]
        Q -->|"影响 1 行"| R["MySQL 库存 +1"]
        R -->|"影响 0 行"| Z4["抛异常 → 事务回滚"]
        R -->|"影响 1 行"| S["seckill_rollback.lua 释放 Redis 预扣"]
        S -->|"名单里有用户 → SREM+INCRBY+DEL，返回 1"| M4["return true → 事务提交"]
        S -->|"不在（已释放过）→ 只删流水，返回 0"| M4
        S -->|"脚本异常 → 返回 null"| Z4
        Z4 -.->|"消费失败重试：Broker 稍后重投"| P
        T["支付回调 payCallback<br/>SET status=2 WHERE id=? AND status=1"] -.->|"支付先赢 → 关单影响 0 行"| Q1
    end

    subgraph S5["3.1 XXL-JOB 对账（每分钟）"]
        U["SCAN seckill:reservation:*"] --> V{"MySQL 有该 orderId？"}
        V -->|"没有且超过 30 秒"| W["用原 orderId 重投 MQ<br/>（两次重投至少隔 60 秒）"]
        V -->|"有且 status=4"| X["release 脚本幂等释放"]
        V -->|"有且未取消"| Y["补回名单 + 删除流水"]
        U --> U2["数量对账：Redis 名单人数 vs MySQL 未取消订单数<br/>不一致 → log.warn"]
        W --> Z5{"本轮有失败？"}
        X --> Z5
        Y --> Z5
        U2 --> Z5
        Z5 -->|"是"| Z6["抛异常 → XXL-JOB 记为失败并按策略重试"]
        Z5 -->|"否"| Z7["本轮完成"]
    end

    E -.->|"写入流水（3.1 的扫描源）"| U
    Q -.->|"2.2 写入的订单状态（4 已取消 / 2 已支付）是 3.1 的对照依据"| U
    S -.->|"与 3.1 分支 X 共用 seckill_rollback.lua"| U
    W -->|"回边：再次进入 2.1"| I
```

**6 → 1：先限流，再进秒杀**

超限请求在切面就被拒绝（`com.hmdp.aspect.RateLimitAspect#around` + `sliding_window.lua`），根本到不了库存逻辑。机制四步：

1. **规则来自注解**：秒杀接口 `@RateLimiter(key="seckill", windowSeconds=1, count=5, dimension="user")`（同一用户 1 秒最多 5 次）；店铺查询用 `dimension="ip"`（同一 IP 1 秒最多 20 次）；
2. **按维度拼 Key**：注解的 key 是前缀、维度拼后缀——秒杀 `key="seckill"` → `seckill:user:{userId}`；店铺 `key="shop:query"` → `shop:query:ip:{ip}`；global 只用前缀；
3. **Lua 原子四步**：`ZREMRANGEBYSCORE` 删窗口外请求 → `ZCARD` 统计窗口内数量 → 达到阈值返回 0；未达到则 `ZADD` 记录本次请求（score=时间戳）并 `EXPIRE` → 返回 1；
4. **返回值判定**：1 = 放行（`joinPoint.proceed()`）；0 或 Redis 异常（null）都拒绝（Fail Closed），前端收到“操作过于频繁，请稍后再试”。

为什么必须用 Lua：`ZCARD` 和 `ZADD` 拆开执行会被并发穿插——窗口内 99 条时两个实例同时判断通过就变成 101 条；Lua 保证「删除 + 统计 + 判断 + 写入」不可分割，同时减少网络往返。

通过后才进入 `VoucherOrderController#seckillVoucher → VoucherOrderServiceImpl#seckillVoucher()`。

**1 → 2：预扣成功才发消息**
`seckill.lua` 原子完成库存校验、用户去重、Redis 库存 -1、`SADD` 名单、`HSET` reservation 流水；只有 Lua 返回 0，Java 才会 `syncSend(orderTopic)`。

**2 → 4：落库成功才发延迟关单消息**
消费者按「订单 ID 幂等 → 用户锁 → user+券校验 → MySQL 条件扣库存 → 保存订单」落库，然后才发 timeout-topic 延迟消息；没落库就不会有关单消息。
（其中「MySQL 条件扣库存」那段代码**属于2.1**：它原本写在 `com.hmdp.service.impl.VoucherOrderServiceImpl#createVoucherOrder` 里，责任链重构后已抽成独立节点 `com.hmdp.service.order.StockDeductNode`（`StockDeductNode.java:23-34`）；1.1 的 4.3 只是从“MySQL 兜底”的设计角度引用它。）

**4 → 5：订单状态是对账的判断依据**
2.2 把订单改成 `status=2`（已支付）或 `status=4`（已取消）；3.1 按状态决定修复方式：查不到订单 → 原 orderId 重投；`status=4` → release 脚本释放；其他 → 补名单 + 删流水。

**5 → 2：还有一条回边**
重投不是终点：3.1 用原 orderId 重投 MQ 后会**再次进入 2.1 的消费者**。这条回边安全的前提是「2.1 消费端幂等」+「3.1 复用原 orderId」。

### 链路二：店铺查询链路（4.1）

```mermaid
flowchart TD
    A["GET /shop/{id}"] --> B0["ShopController#queryShopById<br/>@RateLimiter(key=shop:query, windowSeconds=1, count=20, dimension=ip)"]

    subgraph S6["1.2 限流（与秒杀接口共用同一个 RateLimitAspect 切面和 sliding_window.lua 脚本）"]
        B0 --> B2{"buildKey：ip → shop:query:ip:{ip}"}
        B2 --> B3["EVAL sliding_window.lua<br/>删窗口外 → ZCARD → 未达阈值则 ZADD"]
    end
    B3 -->|"0 / null：拒绝"| B1["Result.fail：操作过于频繁"]
    B3 -->|"1：放行"| C["ShopServiceImpl#queryById<br/>cacheClient.queryWithLogicalExpire(...)"]

    subgraph S3["4.1 逻辑过期读取（CacheClient#queryWithLogicalExpire）"]
        C --> D["GET cache:shop:{id}<br/>String：JSON RedisData{data, expireTime}"]
        D --> E{"Key 存在？"}
        E -->|"不存在"| E2["先查一次库 dbFallback.apply(id)"]
        E2 --> E3{"数据库有？"}
        E3 -->|"没有"| E4["写空值（TTL 2 秒）<br/>返回 null → Result.fail：店铺不存在"]
        E3 -->|"有"| E5["setWithLogicalExpire 写入缓存<br/>返回店铺数据"]
        E -->|"存在"| E6{"值是空字符串（负缓存）？"}
        E6 -->|"是"| E7["返回 null → Result.fail：店铺不存在<br/>不再查库"]
        E6 -->|"否"| G["反序列化 RedisData，取出 data 与 expireTime"]
        G --> H{"expireTime 未过期？"}
        H -->|"是"| I["直接返回缓存值"]
        H -->|"否"| J{"tryLock lock:shop:{id} 成功？<br/>SETNX + 10 秒 TTL"}
        J -->|"成功"| K["提交线程池 CACHE_REBUILD_EXECUTOR<br/>当前请求不等"]
        J -->|"失败"| L["不等锁"]
        K --> M["立即返回旧值"]
        L --> M
    end

    subgraph RB["缓存重建（线程池内）"]
        N["dbFallback.apply(id) → ShopServiceImpl#getById 查 MySQL"]
        O["setWithLogicalExpire：写入 data + 新 expireTime<br/>另加 24 小时物理 TTL 兜底"]
        P["finally unLock(lock:shop:{id})"]
        N --> O --> P
    end
    K -.-> N

    subgraph WR["缓存更新（写路径）"]
        Q["PUT /shop（ShopController#updateShop）"] --> R["ShopServiceImpl#update：updateById 更新 MySQL"]
        R --> S["删除 cache:shop:{id}<br/>先更库再删缓存；删失败由 TTL 收敛"]
    end
```

关键点：

- 店铺查询与秒杀**接口**共用同一个 `RateLimitAspect` 切面和 `sliding_window.lua` 脚本，只是注解参数不同：`dimension` 从 user 换成 **ip**（同一 IP 1 秒 20 次），Key 是 `shop:query:ip:{ip}`；两条规则的计数 ZSet 互相独立；
- 逻辑过期不删 Key：`expireTime` 到了也先返回旧值，只有抢到 `lock:shop:{id}` 的线程去线程池重建；Key 另带 **24 小时物理 TTL 兜底**，防止长期无人访问的 Key 永不过期、无界占用内存（物理过期后走“未命中回填”，有回填 + 负缓存不会打挂数据库）；
- **空值缓存已接入这条链路**：Key 不存在（未预热 / 负缓存过期）→ 查一次库；查不到就写空值（TTL 2 秒）并返回“店铺不存在”，同一个不存在的 ID 在 2 秒内不再打库；查到就按逻辑过期格式写入并返回；
- 写路径是「先更新数据库、再删缓存」，删除失败没有重试，靠下一次读或 TTL 收敛（边界）。

### 链路三：优惠券查询链路（4.2）

```mermaid
flowchart TD
    subgraph L7A["券列表：GET /voucher/list/{shopId}"]
        A1["VoucherController#queryVoucherOfShop"] --> A2{"1.1 Caffeine（localVoucherCache）命中？<br/>shopId → List<Voucher> · 5 秒"}
        A2 -->|"是"| A3["直接返回（不走网络）"]
        A2 -->|"否"| A4{"2.1 GET cache:voucher:list:{shopId}<br/>Redis · 30 秒"}
        A4 -->|"命中"| A5["反序列化 List<Voucher><br/>回填 Caffeine → 返回"]
        A4 -->|"未命中"| A6["4.1 VoucherMapper#queryVoucherOfShop 查 MySQL"]
        A6 --> A7["写 Redis（JSON 数组，TTL 30 秒）<br/>空结果也写 []（天然负缓存）"]
        A7 --> A8["回填 Caffeine → 返回"]
    end

    subgraph L7B["秒杀详情：GET /voucher/seckill/{id}"]
        B1["VoucherController#querySeckillVoucherDetail"] --> B2{"1.1 Caffeine（localSeckillDetailCache）命中？<br/>voucherId → SeckillVoucherDetailDTO · 5 秒"}
        B2 -->|"是"| B3["fillLiveStock 实时读 seckill:stock:{id} → 返回"]
        B2 -->|"否"| B4{"2.1 GET cache:seckill:voucher:{id}<br/>Redis · 30 秒"}
        B4 -->|"命中空值：负缓存"| B5["返回：优惠券不存在<br/>不再穿透到数据库"]
        B4 -->|"命中正常 JSON"| B6["回填 Caffeine<br/>fillLiveStock → 返回"]
        B4 -->|"未命中"| B7["4.1 查 tb_voucher + tb_seckill_voucher 组装 DTO"]
        B7 --> B8{"voucher 存在？"}
        B8 -->|"不存在"| B9["写空值（TTL 2 秒）<br/>返回：优惠券不存在"]
        B8 -->|"存在"| B8B{"seckillVoucher 存在？"}
        B8B -->|"不存在"| B8C["返回：该优惠券不是秒杀券"]
        B8B -->|"存在"| B10["写 Redis（TTL 30 秒）<br/>回填 Caffeine → fillLiveStock → 返回"]
    end

    subgraph WR7["写路径缓存失效"]
        C1["POST /voucher/seckill<br/>VoucherServiceImpl#addSeckillVoucher"] --> C2["写 tb_voucher + tb_seckill_voucher<br/>初始化 seckill:stock:{id}"]
        C2 --> C3["localVoucherCache.invalidate(shopId)<br/>删除 cache:voucher:list:{shopId}"]
    end
```

图里两个 Caffeine 是**两个独立对象**（都定义在 `com.hmdp.service.impl.VoucherServiceImpl`），一条线路用一个：

| 线路   | Caffeine 对象               | Key → Value                           | 对应 Redis Key                        |
| ---- | ------------------------- | ------------------------------------- | ----------------------------------- |
| 券列表  | `localVoucherCache`       | `shopId → List<Voucher>`              | `cache:voucher:list:{shopId}`（30 秒） |
| 秒杀详情 | `localSeckillDetailCache` | `voucherId → SeckillVoucherDetailDTO` | `cache:seckill:voucher:{id}`（30 秒）  |

两者都是 `expireAfterWrite = 5 秒`、`maximumSize = 1000`，互不干扰：查券列表不会碰详情缓存，反之亦然。

关键点：

- **两级缓存的失效方式**：本地 Caffeine 只有 **5 秒 TTL**（不做跨实例广播）；Redis 30 秒；
- **空值缓存在这条链路上真正生效**：秒杀详情查库为空 → 写 `""`（`CACHE_NULL_TTL = 2 秒`）；再查同一个不存在的 ID → 命中空值直接返回，不再打数据库——这就是简历里“缓存空值降低穿透风险”的落点；
- **券列表的空结果也会被缓存**：查库是空列表也会把 `[]` 写进 Redis（30 秒），同 shopId 的重复查询不会回源；
- **库存字段不进缓存**：秒杀详情的 `stock` 每次用 `fillLiveStock` 实时读 `seckill:stock:{id}`（与 1.1 的 Lua 扣减联动）；
- 写路径：新增秒杀券时失效本地 + Redis 的券列表缓存（详情缓存靠 TTL 收敛）。

4.1 是「店铺详情」单 Key 的击穿治理；4.2 是「券列表 / 秒杀详情」的多级缓存抗压 + 不存在 ID 的空值防穿透，两者合起来构成读侧缓存治理。

### 隐藏联系（面试串问的关键）

1. **1.1 写 reservation ←→ 3.1 扫 reservation**：1.1在 Lua 里多写的这条流水，就是3.1的凭据；3.1 的「重投 / 释放 / 补名单」三个分支全靠它；
2. **2.2 的 status ←→ 3.1 的修复方向**：2.2 更新订单状态，3.1 按状态决定「重投还是释放」；
3. **2.2 与 3.1 共用释放脚本**：关单 `closeTimeoutOrder` 和对账分支二执行的是同一个 `seckill_rollback.lua`（幂等、先判 SISMEMBER）；
4. **库存是闭环**：1.1 Redis 预扣 -1 → 2.1 MySQL 条件扣 -1 → 2.2 / 3.1 归还 +1（Redis +1、MySQL +1）；
5. **4.2 与 1.1 的库存联动**：秒杀详情页的 `stock` 字段不进缓存，`fillLiveStock` 每次实时读的 `seckill:stock:{id}` 正是 1.1 写入并扣减的 Key；
6. **1.2 是入口保护**：秒杀按 user 限流、店铺按 IP 限流，保护 1.1 4.1 4.2 三个入口；它只解决「流量多少」，不解决「业务对不对」（防超卖靠 1.1、防重复靠 2.1）。

### 总图

```text
请求 ── 6. 限流（秒杀按 user / 店铺按 IP）
          │
          ├─► 3. 店铺详情：逻辑过期（防击穿）+ 空值（防穿透）
          │
          ├─► 7. 券列表 / 秒杀详情：Caffeine → Redis → MySQL
          │        └── 查不到的 ID 写空值（防穿透）；库存不缓存，fillLiveStock 实时读 seckill:stock ←─┐
          │                                                              │
          └─► 1. Redis Lua 预扣（写 seckill:stock / 名单 / reservation）─┘
                    │
                    ▼
              2. RocketMQ 异步落库（消费端幂等）
                    │
                    ▼
              4. 延迟关单 + 状态条件更新（库存归还，与 3.1 共用 release 脚本）
                    │
                    ▼
              5. XXL-JOB 对账（三分支 + 数量对账）
                    │
                    └── 原 orderId 重投 ──► 回到 2.1（依赖 2.1 的幂等）
```

**一句话背下来**：1.2 是入口保护；1.1 2.1 2.2 组成秒杀主流程；3.1 是主流程的异常兜底；4.1 4.2 是两条查询缓存链路。

---


## 一、库存一致性与流量治理

> 简历第 1 条拆成两问：**1.1 库存一致性**（Redis + Lua 原子预扣，再用 MySQL 条件扣减兜底）解决「不超卖、不重复下单」；**1.2 流量治理**（滑动窗口限流）解决「入口被打爆」。一个是数据正确性，一个是系统可用性。

### 1.1 库存一致性治理：Redis + Lua 原子预扣 + MySQL 条件扣减

*对应简历第 1 条 · 库存一致性部分（原"第一问"）*

> **简历原文**：基于 Redis + Lua 原子完成优惠券库存校验、库存预扣和用户去重，再结合 MySQL 条件扣库存，避免并发场景下库存扣减为负数和同一用户重复下单。

#### 面试话术（四段式：背景 → 剖析 → 构思 → 复盘，可直接背）

**① 背景阐述**：是这样的，我先说一下背景。这个平台是本地生活服务类的，商家可以发布优惠券，其中秒杀券是限量、定点开抢的，比如某天某个时刻上架 200 张。因为折扣力度大，开抢瞬间会有大量流量涌入，这就是典型的秒杀场景。这个场景有两个硬性要求：第一，200 张券只能卖出 200 张，库存绝对不能超卖；第二，这种券限制每个用户只能买一次，也就是一人一单。

**② 问题剖析**：要满足这两个要求，本质上是做好"下单资格校验"——库存够、且用户没买过，才有资格下单。我一开始想到的方案很直接：

- 针对一人一单：去数据库查这个用户在这张券上有没有订单，有就拒绝；为了防止同一个用户并发发起多个请求，还要加分布式锁；
- 针对库存不超卖：扣库存的 update 语句带上 `where stock > 0`，库存扣到 0 就不会再减，自然不会超卖。

这个方案在低并发下完全能用，但秒杀峰值下问题很明显：所有校验、扣减全压在 MySQL 上，大量线程去查库、抢锁、竞争同一条热门库存记录，数据库响应时间被越拖越长，很容易成为整个链路的瓶颈。一句话，资格判断这种高频操作，不应该让 MySQL 来扛。

**③ 方案构思**：所以我的优化思路是——把下单资格的判断整体从 MySQL 挪到 Redis：

- 第一，判断资格只需要"库存"和"这个人买没买过"两个信息，那就把它们放进 Redis：库存用 String 保存（key 是业务前缀加券 ID，value 是剩余库存），已经买过的用户用 Set 保存（value 是所有抢到过的用户 ID）；
- 第二，光放进 Redis 还不够，因为"查库存、查名单、扣库存、记名单"是四步操作，分开执行还是有并发问题。这时候我想到 Lua—Redis 执行 Lua 脚本是原子的，把四步合成一次原子操作，直接从根上解决并发安全，连分布式锁都省掉了；
- 抢券脚本的完整逻辑：先检查库存 Key 是否存在，不存在说明券还没初始化，返回 3；库存小于等于 0 返回 1（库存不足）；用 SISMEMBER 查一人一单，已经买过返回 2；都通过的话，库存预减、把这个用户 SADD 进名单，然后写一条预扣流水（记录订单 ID、用户、券、时间，设置 7 天过期），最后返回 0 表示抢购成功；
- Java 侧拿到 0 之后发 MQ 异步落库（这是下一个优化点），如果发送失败，就执行回滚脚本把库存和资格补偿回去；
- 另一手兜底：消费端真正落库时，数据库扣减仍然保留 `where stock > 0` 条件更新。Redis 是"资格库存"，负责扛高并发；MySQL 是"事实库存"，负责最终一致，两层都扣才安全。

**④ 复盘总结**：这点的核心就是一句话——把高频的资格判断从 MySQL 移到 Redis，并且用 Lua 把"校验 + 预扣"做成一个原子操作，MySQL 只保留最终的持久化和条件兜底。这样既解决了超卖和一人一单的正确性问题，也把接口性能抬上来了。另外我在预扣成功时写了一条流水，这是给后面"预扣了但没落库"的异常场景留的凭据，属于一致性的伏笔。

#### 1. 这条在说什么

秒杀的核心是「抢资格」。资格判断要同时满足两件事：

- 库存还有（不能超卖）；
- 这个人还没抢过（一人一单）。

这两件事如果拆成多条 Redis 命令执行，中间会被其他请求插入：两个请求可能同时读到「库存还有、用户没买过」，然后都往下一步走。所以把「查库存 → 查重复 → 扣库存 → 记名单 → 写流水」整套动作交给**一段 Lua**，在 Redis 里原子执行；抢到资格的请求再进入 MQ/MySQL。MySQL 那边再做一次带条件的扣减，作为最后一道防线。

**这条优化的意义**

- **业务正确性**：秒杀的两个硬约束——库存不能超卖、一人只能一单——在进入数据库之前就被原子判定，不依赖应用锁，也不让数据库承接全部流量；
- **扛住并发**：判断和扣减放在 Redis（内存、单线程执行 Lua），比「MySQL 热点行 + 事务」高一个量级；真正进入 MySQL 的只有预扣成功的那一小批请求；
- **没有它会怎样**：
  - 所有请求直接打 MySQL：热点行竞争、大量失败事务堆积，秒杀接口整体被拖垮；
  - 用 Java「查 → 判 → 扣」：多实例下 `synchronized` 失效，两个请求同时读到「有库存」→ 超卖；
  - 只防超卖不防重复：同一用户脚本连点，一人刷走多张券；
- **本质**：Redis 是「资格库存」（快速拦截、决定谁能进），MySQL 是「持久化库存」（最终事实、兜底不扣成负数），两层职责分离。

#### 2. 完整执行步骤（示例：券 1001，初始库存 100，用户 2）

```mermaid
flowchart TD
    A["Lua 开始"] --> B{"库存 Key 存在?"}
    B -->|"否"| C["return 3 库存未初始化"]
    B -->|"是"| D{"库存 大于 0?"}
    D -->|"否"| E["return 1 库存不足"]
    D -->|"是"| F{"SISMEMBER 在名单?"}
    F -->|"是"| G["return 2 重复下单"]
    F -->|"否"| H["INCRBY -1<br/>SADD 名单<br/>HSET 流水<br/>EXPIRE 7 天"]
    H --> I["return 0 预扣成功"]
```

1. Lua 读取 `seckill:stock:1001` → 值 `"100"`；
2. 判断库存是否大于 0 → 是，继续；
3. `SISMEMBER seckill:order:1001 2` → 用户 2 不在名单，继续；
4. 库存减 1：`INCRBY seckill:stock:1001 -1`，100 变成 99；
5. `SADD seckill:order:1001 2` → 用户 2 加入名单；
6. `HSET seckill:reservation:1001:2 ...` → 写预扣流水（orderId / userId / voucherId / reservedAt / retryCount / lastRetryAt 六个字段）；
7. `EXPIRE seckill:reservation:1001:2 604800` → 流水 7 天有效；
8. 返回 **0**，表示 Redis 预扣成功，Java 侧继续发 MQ 消息；
9. 返回 **非 0** 时：**不发送 MQ**，接口直接返回对应错误（见第 3 节返回值表）。

其中第 3~7 步在同一个 Lua 脚本内连续执行，Redis 单线程执行脚本期间不会插入其他请求的命令。

执行前后 Redis 数据对比（可以用 `GET` / `SMEMBERS` / `HGETALL` 验证）：

| Key                          | 执行前     | 执行后                   |
| ---------------------------- | ------- | --------------------- |
| `seckill:stock:1001`         | `"100"` | `"99"`                |
| `seckill:order:1001`         | 空集合     | `{2}`                 |
| `seckill:reservation:1001:2` | 不存在     | 存在，含 6 个字段，TTL 604800 |

#### 3. 返回值全表（0 / 1 / 2 / 3）

Lua 脚本的返回值就是业务语义，**必须在这里全部说清**：

| 返回值   | Lua 什么时候返回                     | 有没有写 Redis | Java 侧处理（`VoucherOrderServiceImpl.java:88-97`） | 接口返回给用户       |
| ----- | ------------------------------ | ---------- | ---------------------------------------------- | ------------- |
| **0** | 库存扣减、名单、流水全部写完后                | 写了         | 继续 `syncSend` 发订单消息                            | 成功，返回 orderId |
| **1** | 读到库存 ≤ 0 时，扣减之前就返回             | 什么都没写      | `Result.fail("库存不足")`                          | 库存不足          |
| **2** | `SISMEMBER` 命中（用户已在名单），扣减之前就返回 | 什么都没写      | `Result.fail("不能重复下单")`                        | 不能重复下单        |
| **3** | 库存 Key 不存在时（未初始化）              | 什么都没写      | 走默认分支 `Result.fail("秒杀库存未初始化")`                | 秒杀库存未初始化      |
| null  | 脚本执行异常，Java 兜底                 | ——         | code 置为 -1，走默认分支                               | 秒杀库存未初始化      |

两个关键细节：

- **1 / 2 / 3 都在写操作之前返回**，所以失败的请求不会改变任何数据（库存不会被扣、名单不会被加）；
- 返回 3 的原因：库存 Key 是建券时单独初始化的（见下面核心代码 4），不存在的券调用秒杀会得到这个错误。

#### 4. 核心代码

**4.1 Lua 脚本全文（带逐行注释）**（`seckill.lua`）

```lua
---秒杀券id
local voucherId = ARGV[1]        -- Java 传进来的第 1 个参数：券 ID，如 "1001"
--用户id
local userId = ARGV[2]           -- 第 2 个参数：用户 ID，如 "2"
--订单id
local id = ARGV[3]               -- 第 3 个参数：Java 生成的订单 ID（RedisIdWorker）

--库存key（.. 是 Lua 的字符串拼接）
local stockKey = 'seckill:stock:' .. voucherId                       -- seckill:stock:1001
--订单key（一人一单名单）
local orderKey = 'seckill:order:' .. voucherId                       -- seckill:order:1001
--预扣流水key：保存订单与 Redis 预扣之间的绑定关系，供对账任务补偿重投
local reservationKey = 'seckill:reservation:' .. voucherId .. ':' .. userId   -- seckill:reservation:1001:2

--库存是否充足
local stock = redis.call('get', stockKey)
-- GET 返回字符串如 "100"；Key 不存在时返回 false
if (not stock) then
    return 3                     -- 3 = 库存未初始化
end
if (tonumber(stock) <= 0) then
-- tonumber：GET 返回的是字符串，要转成数字才能比大小
    return 1                     -- 1 = 库存不足
end

--判断用户是否下单（一人一单）
if (tonumber(redis.call('sismember', orderKey, userId)) == 1) then
-- SISMEMBER 查名单里有没有这个用户，返回 0 或 1
    return 2                     -- 2 = 重复下单
end

--扣减库存
redis.call('incrby', stockKey, -1)              -- 库存减 1："100" → "99"
--下单（保存用户）
redis.call('sadd', orderKey, userId)            -- 用户加入名单
--记录预扣元数据。Redis Lua 保证库存、用户集合和预扣记录一起提交。
local now = redis.call('time')[1]               -- Redis 服务器时间（秒）
redis.call('hset', reservationKey,              -- 写预扣流水 Hash
        'orderId', id,                          -- 订单 ID（消费者用它做幂等）
        'userId', userId,
        'voucherId', voucherId,
        'reservedAt', now,                      -- 预扣时间
        'retryCount', '0',                      -- 重投次数，初始 0
        'lastRetryAt', '0')                     -- 最近重投时间，初始 0
redis.call('expire', reservationKey, 604800)    -- 7 天后过期
return 0                     -- 0 = 预扣成功
```

**4.2 Java 调用与返回值分支**（`VoucherOrderServiceImpl.java:74-97`）

```java
public Result seckillVoucher(Long voucherId) {
    UserDTO user = UserHolder.getUser();          // 从 ThreadLocal 拿当前登录用户
    if (user == null) {
        return Result.fail("请先登录");            // 没登录直接拒绝
    }

    long orderId = redisIdWorker.nextId("order"); // 先生成全局唯一订单 ID：后面写进流水、发 MQ、当数据库主键
    Long result = stringRedisTemplate.execute(
            SECKILL_SCRIPT,                       // 要执行的 Lua 脚本（seckill.lua）
            Collections.emptyList(),              // KEYS 列表为空：脚本内部自己拼 Key
            voucherId.toString(),                 // ARGV[1] = 券 ID
            user.getId().toString(),              // ARGV[2] = 用户 ID
            String.valueOf(orderId)               // ARGV[3] = 订单 ID
    );
    int code = result == null ? -1 : result.intValue();   // 脚本异常返回 null 时兜底为 -1
    if (code != 0) {                              // 非 0 全部是失败，且脚本没写任何数据
        if (code == 1) {
            return Result.fail("库存不足");        // Lua 返回 1
        }
        if (code == 2) {
            return Result.fail("不能重复下单");    // Lua 返回 2
        }
        return Result.fail("秒杀库存未初始化");   // Lua 返回 3 或 null 兜底
    }
    // code == 0：预扣成功，继续发 MQ（见2.1）
    ...
}
```

**4.3 MySQL 条件扣库存兜底**——**代码属于2.1（消费端），不在秒杀入口**

> 归属说明：这段代码现在的第一执行现场是责任链节点 `com.hmdp.service.order.StockDeductNode`（`StockDeductNode.java:23-34`，重构前写在 `VoucherOrderServiceImpl#createVoucherOrder` 里），由 RocketMQ 消费者 `com.hmdp.listener.SeckillOrderListener#onMessage`（`SeckillOrderListener.java:42`）调用。1.1只讲它的**设计目的**（Redis 之外再加一层 MySQL 兜底）；它的**执行时机**写在2.1。

```java
boolean stockUpdated = seckillVoucherService.update(
        new LambdaUpdateWrapper<SeckillVoucher>()     // 操作的表：tb_seckill_voucher（秒杀券表）
                .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())  // WHERE voucher_id = ?
                .gt(SeckillVoucher::getStock, 0)      // AND stock > 0：关键条件，不是裸扣
                .setSql("stock = stock - 1")          // SET stock = stock - 1
);
// 等价 SQL：UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = ? AND stock > 0
if (!stockUpdated) {                              // 影响 0 行：数据库没库存可扣，和 Redis 对不上
    throw new IllegalStateException("数据库库存与 Redis 预扣状态不一致");  // 抛异常 → 事务回滚 + MQ 重试
}
```

等价 SQL：

```sql
UPDATE tb_seckill_voucher
SET stock = stock - 1
WHERE voucher_id = 1001 AND stock > 0;
```

没有 `stock > 0` 就是裸扣，库存会扣成负数；加上条件后，并发更新由 MySQL 行锁串行化，第二个请求影响 0 行 → 抛异常回滚 → 交给 MQ 重试。Redis 是「资格库存」（快速拦截），MySQL 是「持久化库存」（最终事实），两边都扣是职责分离。

**4.4 库存 Key 的初始化**（`VoucherServiceImpl.java:154`）

```java
//保存秒杀库存到redis
stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
// 新增券时写初始库存；例：券 1001、库存 100 → Key = seckill:stock:1001，值 = "100"
// 后续所有秒杀判断读的都是这个 Key；没初始化过就会返回 3（库存未初始化）
```

建券时写入初始库存，后续所有秒杀判断都读这个 Key。这也解释了返回 3 的场景。

#### 5. 三个 Key 的数据结构

| Key                                        | 类型     | 存什么                 | 谁写                    | TTL |
| ------------------------------------------ | ------ | ------------------- | --------------------- | --- |
| `seckill:stock:{voucherId}`                | String | 剩余库存，如 `"99"`       | 建券写初始值；预扣 -1；补偿/释放 +1 | 无   |
| `seckill:order:{voucherId}`                | Set    | 已抢到的 userId，如 `{2}` | 预扣 `SADD`；释放 `SREM`   | 无   |
| `seckill:reservation:{voucherId}:{userId}` | Hash   | 一次预扣的流水             | 预扣时 `HSET`；对账更新重投信息   | 7 天 |

`{}` 是占位符，不是 Redis 语法：券 1001、用户 2 时，真实 Key 是 `seckill:reservation:1001:2`。

reservation 的 6 个字段（3.1会用到）：

| 字段            | 含义               | 示例                    |
| ------------- | ---------------- | --------------------- |
| `orderId`     | 本次预扣生成的订单 ID     | `1758000000000000001` |
| `userId`      | 用户 ID            | `2`                   |
| `voucherId`   | 券 ID             | `1001`                |
| `reservedAt`  | 预扣时间（秒级时间戳）      | `1789916936`          |
| `retryCount`  | 对账重投次数           | `0`                   |
| `lastRetryAt` | 最近重投时间，`0` = 未重投 | `0`                   |

#### 6. 为什么用 Lua（选型对比）

| 方案 | 决定性因素 | 判定 |
| --- | --- | --- |
| JVM 锁 | 只保护单个 JVM，多实例失效 | 淘汰 |
| 数据库悲观锁 | 热点行串行，持锁占用连接 | 淘汰 |
| 数据库条件扣减 | 无锁竞争，但请求仍要打数据库 | 留作兜底 |
| Redisson 分布式锁 | 每个请求加锁解锁，热点重新串行 | 只用于消费端用户锁 |
| Redis 事务 | 无法按读取结果做条件分支 | 淘汰 |
| **Redis + Lua** | 判断、扣减、去重、写流水一次原子完成 | 采用 |

先列清楚「资格校验 + 预扣」这一步的硬需求，再对比：

| 需求 | 说明 |
| --- | --- |
| 校验与扣减不可分割 | 两个请求不能都看到「库存还有 1」，否则就是超卖 |
| 一次网络往返完成 | 秒杀入口是全链路 QPS 最高的一步，不能 4 个命令跑 4 个 RTT |
| 三个 Key 同生同死 | 库存 `seckill:stock:{id}`、下单名单 `seckill:order:{id}`、预扣流水 `seckill:reservation:{id}:{uid}` 必须一起改，否则对账没有依据 |
| 能按读取结果分支 | 要区分「库存不足 / 重复下单 / 未初始化」，返回不同结果码给调用方 |
| 不引入锁排队 | 热点券下加锁会把入口退化成串行排队 |

**结论：不是分布式锁 / 数据库锁「不行」，是场景不匹配：**

- **JVM 锁**（`synchronized` / `ReentrantLock`）：只保护单个 JVM。服务多副本部署时每个副本各自放行，锁形同虚设；
- **数据库悲观锁**（`SELECT ... FOR UPDATE`）：正确性没问题，但热点券的所有请求排在同一行锁上，持锁期间一直占用数据库连接——等于把 Redis 本该挡住的流量原样放进了数据库，和「削峰」的目标正好相反；
- **数据库条件扣减**：`UPDATE ... WHERE stock > 0` 无锁竞争，但每个请求都要打一次数据库，扛不住入口量级；它在本项目里的定位是**兜底**（消费端那一层），不是入口方案；
- **Redisson 分布式锁**：正确，但每个请求要加锁 + 解锁两次网络往返，热点券下重新串行化，还要处理续期、释放、超时；本项目只把它用在**消费端用户级锁**——那里消息量已经被 MQ 削过，锁竞争可控；
- **Redis 事务**：见下；
- **Redis + Lua**：判断（库存够不够、用户下没下过单）、扣减（`INCRBY -1`）、去重（`SADD`）、写预扣流水（`HSET`）、设 TTL（`EXPIRE`）——五件事在同一次脚本执行里做完（`seckill.lua:38-50`），只花一次网络往返，且不需要任何锁。五条硬需求全中。

**为什么淘汰 Redis 事务（重点展开）**

Redis 事务不是数据库事务那个意思，它只是把命令**攒起来一起发**，缺两件关键能力：

1. **不能在服务端按读取结果做分支**：`MULTI/EXEC` 入队时命令就已经定死了，写不出「如果库存 > 0 就扣减，否则返回 1」——只能先把 `GET` 结果拿回客户端、再由客户端决定发什么命令，于是「判断」和「扣减」之间又裂开一个网络往返的窗口，竞态回来了；
2. **`WATCH` 是乐观锁，冲突要重试**：`WATCH` 库存 Key 能做到「被人改过就别执行」，但并发越高 `EXEC` 返回 nil 的概率越大，客户端要循环重试——热点券下会退化成「大量请求反复重试」，白白放大 Redis 压力；
3. **Lua 是「服务端的一段程序」**：能读、能判断、能写、能返回不同结果码，天然把「检查」和「动作」锁在一个执行单元里——这正是 Redis 提供 `EVAL` 的原因。本项目脚本最后返回 `0/1/2/3` 四个码，调用方据此给不同提示，这是事务做不到的。

两边能力对照：

| 维度 | **Redis + Lua（选）** | Redis 事务（MULTI/EXEC） | Redis 事务 + WATCH | Redisson 分布式锁 |
| --- | --- | --- | --- | --- |
| 网络往返 | 1 次 `EVAL` | 2 次起（必须先 GET 才能判断） | 2 次起，冲突还要重试 | 2 次（加锁 + 解锁） |
| 能否按读取结果分支 | ✅ 脚本内自由 if/else | ❌ 入队时已定死 | ⚠️ 只能在客户端分支 | ✅ |
| 并发下的失败模式 | 无（服务端串行执行） | — | 乐观锁冲突 → 重试风暴 | 锁等待 / 锁超时 |
| 是否引入锁 | 不需要 | 不需要 | 不需要 | 需要（还要续期、释放） |
| 多 Key 原子 | ✅ 同一脚本内 | ✅ | ✅ | 需逐个加锁 |
| 本项目用途 | 秒杀入口（`seckill.lua`）、限流（`sliding_window.lua`） | 未使用 | 未使用 | 只用于消费端用户锁 `lock:order:{userId}` |

一句话：**这一步要的是「判断和动作之间没有缝」，不是「给资源加一把锁」**——Lua 用一次网络往返买到了服务端原子性，而锁只是把并发问题换个地方排队。这就是选 Lua 的根本原因。

**追问：Lua 能保证 Redis 和 MySQL 一起原子吗？** 不能。Lua 只在 Redis 内部原子，跨存储没有事务——「Redis 扣成功、MySQL 没扣成功」这种情况一定会出现，由「发送失败补偿 + 消息幂等 + XXL-JOB 对账」收敛（见 2.1 与 3.1）。

**追问：为什么不用分布式锁锁库存？** 热点券会退化成单锁竞争：所有请求排队等同一把锁，吞吐掉到「一次只处理一个」的量级，还要额外处理锁续期、释放、超时。Lua 不需要锁——Redis 本身就是单线程执行脚本的。

**追问：Redis Cluster 下这段脚本要注意什么？** 脚本操作了 3 个 Key，Cluster 下必须落在同一个槽：要么用 Hash Tag（如 `seckill:{1001}:stock`）把它们绑到同槽，要么拆成多次调用（那就失去原子性了）。当前实现是单机写法，迁移 Cluster 时需要改造——这一点面试要主动说清，别等被问。

#### 7. 边界（面试追问）

| 追问                           | 回答                                                                  |
| ---------------------------- | ------------------------------------------------------------------- |
| Lua 能保证 Redis 和 MySQL 一起原子吗？ | 不能。Lua 只覆盖 Redis；跨存储由消息幂等 + 失败补偿 + 对账收敛                             |
| 为什么不用分布式锁锁库存？                | 热点券会退化成单锁竞争；Lua 不需要锁的续期和释放                                          |
| 一人一单的缺口在哪？                   | 当前没有 `(user_id, voucher_id)` 唯一索引，应用层保证，生产要补                        |
| 入口 `SISMEMBER` 就够了吗？         | 不够。它只拦入口的重复请求；消息重复 / 落库重复由2.1的「订单 ID 幂等 + 用户券查重 + 用户锁」处理（见2.1第 3 节） |
| Redis Cluster 下脚本要注意什么？      | 多 Key 要用 `KEYS` 传入 + Hash Tag 保证同槽；当前是单机写法                          |

---

### 1.2 访问流量治理：滑动窗口限流（IP / 用户维度）

*对应简历第 1 条 · 流量治理部分（原"第六问"）*

> **简历原文**：基于 Redis + Lua + AOP + 自定义注解实现滑动窗口限流，支持 IP 和用户维度，降低秒杀接口被恶意刷取和突发流量冲击的风险。

#### 面试话术（四段式：背景 → 剖析 → 构思 → 复盘，可直接背）

**① 背景阐述**：这个项目需要限流的场景不止一个，我梳理了三种情况：

- 秒杀接口：开抢瞬间流量极大，需要限流保障系统的可用性（用户维度）；
- 领券类写接口：可能被脚本恶意刷券，需要按用户限流；
- 商家信息查询接口：会被爬虫高频抓取，需要按 IP 限流。

所以系统需要一个支持多维度的限流组件，把无效流量挡在业务之前。

**② 问题剖析**：要做这个组件，我先明确了几条硬性约束：

- 无侵入：不能在每个接口里手写限流判断，否则重复代码多、容易漏；
- 高性能：限流本身不能成为瓶颈；
- 多实例一致：服务是多实例部署，单机限流各限各的，总量会失控，同一个用户打到不同实例就能绕过去；
- 易用：加个注解、改几个参数就能接入。

也就是说：**要的是一个“放之各接口皆准”的限流组件，而不是给每个接口定制的限流代码。**

**③ 方案构思**：围绕这几条约束做技术映射和选型：

- 先定实现骨架：无侵入 + 易用 → AOP 切面 + 自定义注解；高性能 + 多实例一致 → Redis 做计数中心；
- 再选算法，四种我都分析过：固定窗口有临界点问题，窗口切换的瞬间能通过接近两倍的流量；漏桶强制请求按固定速率流出，完全不处理突发——秒杀恰恰是突发场景，有资源也用不上，还会大量误丢；令牌桶允许突发，但反过来会被“攒满令牌再瞬间爆发”的脉冲攻击利用，它更适合网关限速那种“限平均速率”的场景；滑动窗口记录每次请求的真实时间戳，任意时间窗口内精确计数，既平滑又能抑制脉冲攻击——选它；
- 落地成三层实现：① 自定义注解 `@RateLimiter`，参数是 key 前缀、窗口大小（秒）、窗口内允许的请求数、限流提示信息、限流维度（全局 / 用户 / IP）；② AOP 切面拦截标注了注解的方法，按维度拼出完整限流 key（用户维度取登录上下文的 userId，如 `rate:limit:user:{userId}`；IP 维度优先取 X-Forwarded-For、再取 remoteAddr；全局维度只用前缀），并把窗口、上限、当前毫秒时间戳传给 Lua；③ Lua 脚本三步原子执行——`ZREMRANGEBYSCORE` 清掉窗口外的旧记录，`ZCARD` 统计当前窗口内的请求数，没超上限就 `ZADD` 记录本次请求并返回 1 放行、超了就返回 0 拒绝；member 用“毫秒时间戳 + 随机数”保证唯一（否则同一毫秒的请求会互相覆盖、ZCARD 数不准），最后给 ZSet 设置过期时间防止冷 key 占内存；
- 实际配置：秒杀接口按用户 1 秒 5 次，店铺详情按 IP 1 秒 20 次，数字后续按压测和业务调整。

**④ 复盘总结**：这套组件做到了注解 + AOP 零侵入、Redis + Lua 多实例一致且原子、滑动窗口兼顾平滑与防脉冲。选型原则一句话——**限流算法没有优劣，只有匹配场景：防刷要的是“任意时间窗口内的次数硬上限”，滑动窗口最贴合**。它解决的是“流量多少”的问题，而业务是否正确（不超卖、不重复）由前面的 Redis + Lua 预扣和消费端幂等保证，两者分工明确。

#### 1. 这条在说什么

限流和防超卖解决的不是同一个问题：Lua 库存脚本只能保证进入业务后的库存正确，拦不住大量恶意请求占用 Web 线程、Redis 连接和带宽。所以要在接口执行前再挡一道。

秒杀接口按**用户**限流（防脚本刷单），商铺查询按 **IP** 限流（防爬虫），另外支持**全局**维度。

**这条优化的意义**

- **保护资源**：接口执行前拦掉恶意和超量请求，保护 Web 线程、Redis 连接、带宽这些稀缺资源；
- **保护后方**：限流是第一道闸门——被拦下的请求根本到不了 Lua 预扣和 MQ，相当于给整条链路减压；
- **没有它会怎样**：脚本以每秒几百次刷秒杀接口，把线程池和 Redis 连接占满，正常用户请求排队超时；商铺接口被爬虫批量抓取同理；
- **和其他点的分工**：限流只解决「流量多少」（拦恶意 / 突发），不解决「业务对不对」（防超卖靠第一节的 Lua，防重复靠第二节的幂等），两者是前后道防线，不能互相替代。

#### 2. 滑动窗口完整执行步骤

```mermaid
flowchart LR
    A["@RateLimiter 注解"] --> B["RateLimitAspect#around"]
    B --> C["按 dimension 拼 Key"]
    C --> D["EVAL sliding_window.lua"]
    D -->|"1"| E["执行业务"]
    D -->|"0 / null"| F["拒绝：操作过于频繁"]
```

**图 1：整体调用流程（含参数与返回值分支）**

```text
HTTP POST /voucher-order/seckill/1001
        │
        ▼
┌──────────────────────────────────────────────┐
│ VoucherOrderController                       │
│ @RateLimiter(                                │
│   key = "seckill",                           │
│   windowSeconds = 1,                         │
│   count = 5,                                 │
│   dimension = "user"                         │
│ )                                            │
│ public Result seckillVoucher(Long voucherId) │
└──────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────┐
│ RateLimitAspect.around()                     │
│                                              │
│ 1. 读取注解参数                              │
│    key = "seckill"                           │
│    windowSeconds = 1                         │
│    count = 5                                 │
│    dimension = "user"                        │
│                                              │
│ 2. buildKey() 构建 Redis Key                 │
│    dimension = "user"                        │
│    → "seckill:user:1001"                     │
│                                              │
│ 3. 执行 Lua 脚本                             │
│    EVAL sliding_window.lua 1                 │
│         seckill:user:1001 1 5 now            │
└──────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────┐
│ Redis 执行 sliding_window.lua                │
│                                              │
│ ① zremrangebyscore 清理窗口外旧记录          │
│ ② zcard 统计窗口内请求数                     │
│ ③ if current >= limit then return 0         │
│    else                                     │
│      zadd 记录本次请求                      │
│      expire 设置 key 过期时间               │
│      return 1                               │
│    end                                      │
└──────────────────────────────────────────────┘
        │
   ┌────┴────┐
   ▼         ▼
返回 0      返回 1
   │         │
   ▼         ▼
┌──────────────┐   ┌──────────────────────────┐
│ Result.fail  │   │ joinPoint.proceed()      │
│ "操作过于频繁"│   │ 继续执行原 Controller 方法│
│ 请稍后再试"  │   │ 返回真实业务结果          │
└──────────────┘   └──────────────────────────┘
```

1. 请求进入 Controller 方法前，被切面 `RateLimitAspect` 拦截；

2. 按注解的 `dimension` 拼限流 Key——**dimension 决定「计数单位」：同一维度的实体共用一个 Key，不同实体各限各的**：
   
   | dimension | 谁共用一个 Key | 项目实际 Key（示例）                 | 效果                                            |
   | --------- | --------- | ---------------------------- | --------------------------------------------- |
   | `user`    | 一个用户一个    | `seckill:user:1001`          | 用户 1001 每秒最多 5 次；用户 1002 走自己的 Key，不占 1001 的名额 |
   | `ip`      | 一个 IP 一个  | `shop:query:ip:1.2.3.4`      | 这个 IP 每秒最多 20 次；换 IP 就换 Key                   |
   | `global`  | 所有人共用 1 个 | `rate:limit`（注解 key 原样，不加后缀） | 全站合计每秒最多 N 次                                  |
   
   拼接规则（`RateLimitAspect#buildKey`）：以注解 `key` 为前缀，`user` 加 `:user:{userId}`、`ip` 加 `:ip:{clientIp}`、`global` 不加后缀。
   
   > 注解默认前缀是 `rate:limit`（`String key() default "rate:limit"`），所以此前示意的 `rate:limit:user:1001` 是「默认前缀」版本；本项目秒杀接口用 `key="seckill"`、商铺接口用 `key="shop:query"`，真实 Key 是 `seckill:user:1001` / `shop:query:ip:1.2.3.4`。
   
   具体跑一遍（用户 1001 请求秒杀）：拼出 `seckill:user:1001` → Lua 在这个 ZSet 上数最近 1 秒的请求数 → 小于 5 记录并放行，达到 5 返回 0 拒绝；用户 1002 走 `seckill:user:1002`，完全独立。

3. 执行 `sliding_window.lua`（四步原子操作）：
   
   - `ZREMRANGEBYSCORE` 删除窗口外的旧请求记录；
   
   - `ZCARD` 统计当前窗口内的请求数；
   
   - 达到阈值 → 返回 0（拒绝）；
   
   - 未达到 → `ZADD` 记录本次请求，并给 Key 设置过期时间，返回 1；

4. 返回 1 → 执行业务方法；返回 0 或 Redis 异常 → 返回「操作过于频繁，请稍后再试」。

**为什么这四步必须放 Lua**：假设阈值 100，当前 ZSet 里有 99 条。两个应用实例同时执行：

```text
实例 A：ZCARD → 99
实例 B：ZCARD → 99
实例 A：判断可放行 → ZADD
实例 B：判断可放行 → ZADD
最终窗口内 101 条，超限
```

单条 Redis 命令原子，不代表「删除 + 统计 + 判断 + 写入」组合原子，必须用 Lua 包起来；顺带也减少了多次网络往返。

**图 3：时序图**

```text
Client          RateLimitAspect        Redis(Lua)         Controller
  │                    │                   │                   │
  │──seckill/1001─────►│                   │                   │
  │                    │                   │                   │
  │                    │──EVAL lua────────►│                   │
  │                    │  KEYS[1]          │                   │
  │                    │  ARGV[1..3]       │                   │
  │                    │                   │                   │
  │                    │◄──return 1────────│                   │
  │                    │                   │                   │
  │                    │──joinPoint.proceed()──────────────────►│
  │                    │                   │                   │
  │◄───Result──────────────────────────────────────────────────│
  │                    │                   │                   │
  │                    │                   │                   │
  │  （如果 Lua 返回 0）│                   │                   │
  │                    │◄──return 0────────│                   │
  │◄──Result.fail──────│                   │                   │
  │  "操作过于频繁"     │                   │                   │
```

一句话总结：**请求进来 → AOP 拦截 → 读注解 → 拼 Key → 执行 Lua → Lua 用 ZSet 做滑动窗口判断 → 返回 1 放行、返回 0 拒绝。** 整个过程原子、分布式、可配置。

#### 3. 为什么选滑动窗口（选型对比）

| 算法 | 决定性因素 | 判定 |
| --- | --- | --- |
| 固定窗口 | 窗口边界可能瞬间通过双倍流量 | 淘汰 |
| 漏桶 | 请求按固定速率流出，不允许合理突发 | 淘汰 |
| 令牌桶 | 允许突发，但挡不住「攒额度瞬间爆发」，无法精确限制任意时间段内次数 | 网关平均速率场景适用 |
| **滑动窗口日志（ZSet）** | 每次请求记录时间戳，任意时间窗口内精确计数 | 防刷场景采用 |
| 滑动窗口计数 | 省内存但是近似值 | 高 QPS 优化方向 |

防刷要的是精确和均匀：黄牛可以攒着额度在某一秒集中爆发，令牌桶会全部放行；滑动窗口记录每个请求的真实时间戳才能拦住。

先列清楚这个项目对限流方案的硬需求，再对比：

| 需求 | 说明 |
| --- | --- |
| 任意窗口精确计数 | 口径要能说成硬指标——「同一用户 1 秒内最多 5 次」，不能是「大概 5 次」 |
| 抑制「攒额度脉冲」 | 脚本/黄牛的典型手法是安静几秒攒额度、再瞬间爆发，专打「平均速率」型限流 |
| 多实例共享计数 | 服务多副本部署，限流额度必须全局唯一，否则额度 = 配置值 × 副本数 |
| 维度可切换 | 秒杀按用户、店铺详情按 IP，还要能全局兜底 |
| 业务零侵入 | 限流是横切关注点，不能写进 Controller 方法体，参数最好写在注解上 |

**结论：不是固定窗口 / 令牌桶「不行」，是场景不匹配：**

- **固定窗口**：最便宜（一个 `INCR` + `EXPIRE`），很多网关就用它；但它有「临界突刺」——窗口 1 秒、上限 5 次时，在第 1 秒末尾打 5 次、第 2 秒开头再打 5 次，**真实的 0.02 秒里通过了 10 次**。第一条硬需求就不满足；
- **漏桶**：把请求按固定速率削平，而秒杀本身就是突发场景——正常用户会被排队（体验差）或直接丢弃（误伤），而且它不区分「恶意刷」和「正常抢」。适合「保护下游、允许排队」的场景，不适合身份维度限次；
- **滑动窗口计数**：只存两个窗口的计数、按重叠比例加权估算，内存 O(1)，但结果是**近似值**，且隐含「请求在窗口内均匀分布」的假设。属于高 QPS 下的优化方向，本项目还没到需要省这点内存的程度；
- **令牌桶**：限的是**平均速率**，不是**任意窗口次数**，恰好放过第一条硬需求要防的脉冲攻击，下面单独展开；
- **滑动窗口日志**：每次请求记录真实时间戳（`score = 毫秒时间戳`，`member = 时间戳 + 随机数`），窗口左边界跟着当前时间滑动，所以「任意 1 秒内 ≤ 5 次」在数学上成立、不是近似。五条硬需求全部命中，见下表。

**为什么淘汰令牌桶（重点展开）**

令牌桶是最容易被认为「应该够用」的方案，所以要讲透。它的模型是：桶里存令牌，按固定速率补充，请求来了先拿令牌，拿不到就拒绝；桶容量决定能容纳多大的瞬时突发。问题出在这个「容量」上：

1. **先花满桶**：桶容量 C 决定「最多能攒多少额度」。攻击者只要安静等几秒，就能一次性拿到 C 次放行——安静越久攒得越满；
2. **再靠这一秒补的令牌**：补充速率 R 决定持续能力。所以在任意 1 秒内，累计最坏是 C（满桶突发）+ R（这一秒补进来的）= **C + R 次**。配成「5 次/秒」（C = R = 5）时，实际允许 **1 秒 10 次**——和固定窗口的 2 倍突刺是同一个数量级的问题，只是换了个实现方式；
3. **它的设计目标本来就不是防刷**：令牌桶是为「保护下游别被打穿」设计的——限平均速率、允许合理突发。拿它做身份维度防刷，等于承认「攒额度爆发」是合法流量。

两边能力对照：

| 维度 | **滑动窗口日志（选）** | 令牌桶 |
| --- | --- | --- |
| 限制的是什么 | 任意时间窗口内的**次数** | **平均速率**（长期平均） |
| 任意 1 秒内最坏通过次数 | 恒等于 `count`（配置 5 就是 5） | **容量 + 速率**（C = R = 5 时是 10） |
| 能否抑制「攒额度脉冲」 | 能：上一秒用掉的额度不会因为跨窗口而重置 | 不能：安静几秒攒满桶，瞬间全部放行 |
| 配置语义 | `windowSeconds=1, count=5` 直接对应「1 秒 5 次」 | 容量与速率两个参数共同决定行为，语义不直观 |
| 数据结构 | ZSet，member = 毫秒时间戳 + 随机数 | 令牌计数 + 上次补充时间 |
| 本项目怎么落地 | `sliding_window.lua` + `@RateLimiter` + AOP（第 4 节） | Redisson `RRateLimiter` 就是它，语义不匹配 |

用同一串请求验算，两条硬需求的失败模式各打中谁（窗口 1 秒、上限 5 次）：

| 场景 | 固定窗口 | **滑动窗口日志（选）** | 令牌桶（C = R = 5） | 漏桶（5/s） |
| --- | --- | --- | --- | --- |
| 第 0.90 秒连发 5 次，第 1.05 秒再连发 5 次（跨窗口边界） | **放行 10 次** | **放行 5、拒绝 5**（1.05 秒时的窗口是 [0.05, 1.05]，里面已有那 5 次） | 放行 5、拒绝 5（0.15 秒只补回 0.75 个令牌） | 放行 5，其余排队/丢弃 |
| 空闲 2 秒后，1 秒内持续打 10 次 | 放行 5 | **放行 5、拒绝 5** | **放行 10 次**（满桶 5 + 这一秒补的 5） | 放行 5，其余排队 |

第一行是「边界突刺」，打的是固定窗口；第二行是「攒额度脉冲」，打的是令牌桶。**本项目要防的两种攻击，正好是这两种算法各自的失败模式**。

**为什么不用现成的限流组件**

| 组件 | 算法 | 分布式 | 不用它的原因 |
| --- | --- | --- | --- |
| Guava `RateLimiter` | 令牌桶 | ❌ 单机 JVM 内存 | 3 个副本就是 3 × 5 次/秒，额度被副本数放大 |
| Redisson `RRateLimiter` | 令牌桶 | ✅ 基于 Redis | 语义不匹配（见上）；维度拼 key、注解参数仍要自己写。**不是怕引依赖**——Redisson 本来就在依赖里（`UserLockNode` 的分布式锁在用） |
| Sentinel | 滑动窗口**计数**（`LeapArray` 分桶） | ✅ | 为两个接口引入整套流控框架 + 控制台 + 规则推送，能力过剩，且是近似计数 |
| Nginx `limit_req` | 漏桶（`burst` 参数） | ✅ 网关层 | 只能拿到 IP，拿不到业务身份（userId 来自登录态）；与 `@RateLimiter` 的参数体系不统一 |
| 云 WAF / CDN 防护 | 厂商黑盒 | ✅ 边缘 | 适合扛 DDoS/CC，不感知「同一用户同一张券」的业务语义 |

**这套方案的代价（诚实版）**

| 代价 | 真实情况（可核验） |
| --- | --- |
| 单 key 内存 | 有上限、而且很小：超限的请求**不写入** ZSet（`sliding_window.lua:12-14` 先 `return 0`），所以单个 key 的元素数最多就是 `count` 个——秒杀维度 5 个、店铺维度 20 个；元素少时 Redis 用紧凑编码 |
| 真正的内存成本 | 不是单 key 大小，而是 **key 的数量** = 活跃用户数 / IP 数，靠 `EXPIRE key window`（`sliding_window.lua:17`）回收冷 key（秒杀 key 只活 1 秒） |
| 原子性与集群 | 清理窗口外 → 计数 → 未超限才写入 → 续期，四步在同一个 Lua 脚本里，Redis 单线程执行；只用了 1 个 key（`KEYS[1]`），Cluster 下也落在同一 slot |
| Fail Closed（宁可拒绝） | Redis 异常或脚本返回空时按**拒绝**处理：`allowed == null \|\| allowed == 0L → Result.fail("操作过于频繁，请稍后再试")`（`RateLimitAspect.java:50-52`）。代价是 Redis 抖动时会误伤正常用户，换来的是「限流器自己挂了也不能让后端失去保护」 |
| IP 维度的前提 | `clientIp()` 优先取 `X-Forwarded-For` 的第一段，取不到才用 `remoteAddr`（`RateLimitAspect.java:68-82`）——**必须由可信网关覆写**；服务直接暴露、客户端能自带 XFF 时，IP 维度可被伪造绕过 |
| 未登录用户的桶 | user 维度取不到登录态时 `userId` 记为 `0`（`RateLimitAspect.java:62`），所有未登录请求共用 `rate:limit:user:0` 一个桶。秒杀接口本身有登录拦截，正常走不到 |
| 注解默认值的坑 | 不写参数就是**全局 60 秒 10 次**（`RateLimiter.java:16-19`）——漏写 `dimension` 会从「按用户」悄悄变成「全局」 |
| 覆盖面 | 目前只挂两个接口：`VoucherOrderController.java:27-28`（秒杀，`key="seckill"`、1 秒 5 次、user 维度）和 `ShopController.java:35-36`（店铺详情，`key="shop:query"`、1 秒 20 次、ip 维度）；**优惠券列表/详情接口没有挂**，别说成全站限流 |

一句话：**判断标准不是「有没有现成的」，而是「现成的语义对不对」**——本项目要的是「任意窗口精确次数 + 按业务身份分维度」，这两条决定了必须自己写那段 Lua；而选用 Redis 而不是本机缓存，是因为计数必须多副本共享。这就是选滑动窗口日志、不选令牌桶和固定窗口的根本原因。

**追问：那 Redis 挂了限流不就失效了？** 现在是 Fail Closed——脚本返回空按拒绝处理（`RateLimitAspect.java:50-52`）。这是有意的取舍：限流器是用来保护后端的，它自己不可用时，宁可让少量请求失败并明确提示「操作过于频繁」，也不能让后端裸奔；代价是 Redis 抖动时会误伤正常用户。

**追问：ZSet 会不会把 Redis 内存吃满？** 单个 key 不会——超限请求不写入，元素数上限就是 `count`；要关注的是 key 的数量（活跃用户/IP 数），靠 TTL 回收。真到了 `count` 配到万级、且流量持续打满的场景，应该换成滑动窗口计数（近似但 O(1) 内存），这也是它被列为「高 QPS 优化方向」的原因。

**追问：为什么不做在网关？** 网关能做粗粒度的全局保护，但它拿不到业务身份——`dimension="user"` 需要登录态里的 userId，只有业务层才有。生产上两层并用：网关扛 IP/连接数级别的洪峰，业务层按用户/券维度精确限次。

#### 4. 核心代码

**4.1 自定义注解**（`RateLimiter.java:12-20`）

```java
@Target(ElementType.METHOD)           // 只能加在方法上
@Retention(RetentionPolicy.RUNTIME)   // 运行期保留，切面才能通过反射读到
@Documented
public @interface RateLimiter {
    String key() default "rate:limit";        // 限流 Key 前缀
    long windowSeconds() default 60;          // 窗口大小（秒）
    int count() default 10;                   // 窗口内允许的请求数
    String dimension() default "global";      // 维度：global / user / ip
}
```

**4.2 使用位置**（秒杀按用户、商铺按 IP）

```java
// VoucherOrderController.java:27-31
@PostMapping("seckill/{id}")
@RateLimiter(key = "seckill", windowSeconds = 1, count = 5, dimension = "user")
// 秒杀接口：同一个用户 1 秒内最多 5 次
public Result seckillVoucher(@PathVariable("id") Long voucherId) {
    return voucherOrderService.seckillVoucher(voucherId);
}

// ShopController.java:35-39
@GetMapping("/{id}")
@RateLimiter(key = "shop:query", windowSeconds = 1, count = 20, dimension = "ip")
// 商铺查询：同一个 IP 1 秒内最多 20 次
public Result queryShopById(@PathVariable("id") Long id) {
    return shopService.queryById(id);
}
```

**4.3 限流脚本**（`sliding_window.lua` 全文）

```lua
-- KEYS[1]: 限流 Key，如 seckill:user:1001
-- ARGV[1]: 窗口秒数    ARGV[2]: 阈值    ARGV[3]: 当前毫秒时间戳
local key = KEYS[1]
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

redis.call('zremrangebyscore', key, 0, now - window * 1000)
-- 删除 score 在窗口外的成员（窗口外的旧请求不再计数）
local current = redis.call('zcard', key)                     -- 统计窗口内请求数
if current >= limit then
    return 0                                                 -- 达到阈值：拒绝
end

redis.call('zadd', key, now, now .. '-' .. math.random(100000, 999999))
-- 记录本次请求：score = 时间戳；member = 时间戳-随机数（防同毫秒覆盖）
redis.call('expire', key, window)                            -- Key 过期时间 = 窗口大小
return 1                                                     -- 放行
```

**4.3 补充：脚本逐行拆解（参数从哪来、ZSet 存什么、限流拦在哪）**

**图 2：Lua 滑动窗口内部原理**

以 `key = "seckill:user:1001"`、窗口 `1 秒`、限制 `5 次` 为例。

```text
时间轴 ─────────────────────────────────────────────────────────────►
        │                                                             │
        │  1 秒窗口                                                    │
        │                                                             │
        │  now - 1000ms                        now                    │
        │      │                                │                     │
        ▼      ▼                                ▼
   ────┬────────────────────────────────────────┬───────────────►
       │                                        │
       │  窗口内保留的请求记录                    │
       │                                        │
       ▼                                        ▼
   ZSET:  [ 旧请求1 ] [ 旧请求2 ] ... [ 旧请求5 ]  [ 本次新请求 ]
            │
            └── 如果 now - 1000ms 之前的记录，全部删除
```

滑动窗口的时间变化：

```text
第 0.0 秒：请求 A → zadd，当前窗口 [A]
第 0.2 秒：请求 B → zadd，当前窗口 [A, B]
第 0.5 秒：请求 C → zadd，当前窗口 [A, B, C]
第 0.8 秒：请求 D → zadd，当前窗口 [A, B, C, D]
第 0.9 秒：请求 E → zadd，当前窗口 [A, B, C, D, E]
第 1.0 秒：请求 F → zcard = 5 >= 5 → return 0，拒绝
第 1.1 秒：请求 G → zremrangebyscore 删除 0.1 秒之前的记录
                    A 被删除，窗口变成 [B, C, D, E]
                    zcard = 4 < 5 → 允许，zadd G
```

窗口是**滑动**的：每来一个请求，窗口左边界都跟着 `now` 前移，不会出现固定窗口那种「前一秒末尾 + 下一秒开头瞬间打满两倍额度」的突刺。

**① 参数怎么进 Lua 的：注解 → 切面 → `execute()` → EVAL → KEYS/ARGV**

```text
@RateLimiter(key="seckill", windowSeconds=1, count=5, dimension="user")
   │  a. 注解参数挂在方法上（RUNTIME 保留）
   ▼
RateLimitAspect.around()
   │  b. 反射读出注解实例
   │     signature.getMethod().getAnnotation(RateLimiter.class)
   ▼
stringRedisTemplate.execute(
    SCRIPT,
    Collections.singletonList(key),               // → KEYS[1] = seckill:user:1001
    String.valueOf(limiter.windowSeconds()),      // → ARGV[1] = "1"
    String.valueOf(limiter.count()),              // → ARGV[2] = "5"
    String.valueOf(System.currentTimeMillis())    // → ARGV[3] = "1789916936100"
);
   │  c. Spring Data Redis 拼成一条 EVAL 命令
   ▼
EVAL <脚本内容> 1 seckill:user:1001 1 5 1789916936100
                 └ KEYS 个数      └── ARGV[1..3]，按位置对应
```

规则：`execute(脚本, keys列表, 可变参数...)` —— **keys 列表进 `KEYS[]`，后面的可变参数按顺序进 `ARGV[]`**。Redis 参数都是字符串，Lua 里用 `tonumber` 转回数字。`KEYS` 和 `ARGV` 只是占位变量，Lua 文件里没有配置，值每次调用现装。

**② ZSet 里到底存了什么**

每个被限对象（一个用户 / 一个 IP / 全局）各有一个 ZSet，内容只有两栏：

| 元素部分          | 值                                  | 来源                                                   |
| ------------- | ---------------------------------- | ---------------------------------------------------- |
| **score**     | 请求时间戳（毫秒）                          | `ARGV[3]`（Java `currentTimeMillis()`）                |
| **member（值）** | `时间戳-随机数`，如 `1789916936100-483920` | Lua 现场拼接：`now .. '-' .. math.random(100000, 999999)` |

```text
seckill:user:1001 (ZSet)
member                    score
"1789916936100-483920" →  1789916936100
"1789916936250-102384" →  1789916936250
"1789916936400-999001" →  1789916936400
```

两个设计原因：

- **score 用时间戳**：`zremrangebyscore` 要按时间范围删记录，时间就是窗口的坐标轴；
- **member 拼随机数**：ZSet 的 member 必须唯一。同一毫秒来了两个请求，若 member 都用时间戳，第 2 条会**覆盖**第 1 条（ZADD 对已存在 member 是更新而不是新增）→ `zcard` 少算一个 → 超限放行。

一句话总结：**member 的内容不重要，确保唯一才重要**。它不需要被任何人读取，职责只是让每次请求各占一个独立元素，`zcard` 数出来的才等于请求数：

```text
member 固定（永远写 "1"）：每次 zadd 都只是更新同一个成员的 score → zcard 永远是 1 → 限流彻底失效
member 用裸时间戳：同一毫秒的并发请求互相覆盖 → 5 个请求只留 1 个成员 → 超限放行
member 用 时间戳-随机数：每请求一个成员 → zcard = 请求数 → 计数正确
```

（更规范的做法是应用侧生成请求唯一 ID 传进来，效果同理；这里用时间戳+随机数是为了不出 Lua 就能保证唯一。）

**③ 四行命令各干什么**

| 行   | 命令                                       | 作用                               | 解决的问题   |
| --- | ---------------------------------------- | -------------------------------- | ------- |
| 1   | `zremrangebyscore key 0 now-window*1000` | 删除 score ≤ 窗口左边界的旧请求             | 窗口「滑动」  |
| 2   | `zcard key`                              | 数窗口内请求数，`>= limit` 则 `return 0`  | 判定      |
| 3   | `zadd key now member`                    | 记录本次请求（score=now、member=now-随机数） | 写入/计数来源 |
| 4   | `expire key window`                      | TTL 续期，闲置自动回收整个 Key              | 内存清理    |

第 1 行的时间轴（window=1 秒、now=1789916936100，cutoff=1789916935100）：

```text
1789916934000 ✗ 删   1789916934900 ✗ 删 │ 1789916935200 ✓ 留   1789916935800 ✓ 留
                            cutoff ────┘ 窗口内（最近 1 秒）参与 zcard 计数
```

- 不删的后果：`zcard` 会把历史所有请求都数上，用户发满 5 次后**永远**被限——那是「一生 5 次」不是「每秒 5 次」；
- 第 4 行不写不影响计数正确性，但闲置用户的 Key 会永久留在 Redis 里；有它则「window 秒没有新请求 → 整个 Key 自动消失 → 下次从 0 开始」。

**④ 返回值 0/1 拦在哪：拦在切面，不在 Lua**

Lua 只是「裁决器」，真正拒绝请求的动作在 `around()`：

```java
Long allowed = stringRedisTemplate.execute(SCRIPT, ...);

if (allowed == null || allowed == 0L) {
    return Result.fail("操作过于频繁，请稍后再试");   // return 0：直接返回，目标方法不执行（限流发生点）
}
return joinPoint.proceed();                          // return 1：放行
```

**⑤ 把 6 个请求跑一遍（count=5）**

```text
第 1 个请求：zcard=0 → 0<5 → zadd 记一笔 → return 1 → proceed() 执行业务
...
第 5 个请求：zcard=4 → 4<5 → zadd 记一笔 → return 1 → proceed()
第 6 个请求：zcard=5 → 5>=5 → return 0 → Result.fail("操作过于频繁")   ← 被限流
1 秒后：下一次请求先 zremrangebyscore 删掉过期记录，zcard 归 0 → 又可放行 5 次
```

**4.4 切面：统一执行限流**（`RateLimitAspect.java:38-66`）

```java
private static final DefaultRedisScript<Long> SCRIPT;     // ① 声明一个静态常量，类型是 DefaultRedisScript<Long>，泛型 Long 表示 Lua 脚本返回 Long 类型；static final 表示类级别唯一且不可变，类加载时初始化。
static {                                                 // ② 静态初始化块：JVM 在类加载时执行且只执行一次，用于给 SCRIPT 赋值。
    SCRIPT = new DefaultRedisScript<>();                 // ③ 创建 DefaultRedisScript 实例，<> 是菱形语法，自动推断泛型为 Long。
    SCRIPT.setLocation(new ClassPathResource("sliding_window.lua")); // ④ 指定 Lua 脚本文件的位置：从 classpath 下读取 sliding_window.lua。
    SCRIPT.setResultType(Long.class);                    // ⑤ 设置脚本返回值的类型为 Long，这样 execute 返回的就是 Long，而不是 Object。
}

@Around("@annotation(com.hmdp.annotation.RateLimiter)")   // 拦截所有带 @RateLimiter 的方法
public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    RateLimiter limiter = signature.getMethod().getAnnotation(RateLimiter.class);   // 读注解参数
    String key = buildKey(limiter);                       // 按维度拼限流 Key
    Long allowed = stringRedisTemplate.execute(
            SCRIPT,                                       // sliding_window.lua
            Collections.singletonList(key),               // KEYS[1]
            String.valueOf(limiter.windowSeconds()),      // ARGV[1] 窗口秒数
            String.valueOf(limiter.count()),              // ARGV[2] 阈值
            String.valueOf(System.currentTimeMillis())    // ARGV[3] 当前毫秒时间戳
    );
    if (allowed == null || allowed == 0L) {               // 0 = 超阈值；null = Redis 异常
        return Result.fail("操作过于频繁，请稍后再试");      // Fail Closed：宁可拒绝
    }
    return joinPoint.proceed();                           // 放行，执行原业务方法
}

private String buildKey(RateLimiter limiter) {
    String base = limiter.key();
    if ("ip".equalsIgnoreCase(limiter.dimension())) {
        return base + ":ip:" + clientIp();                // 例：shop:query:ip:1.2.3.4
    }
    if ("user".equalsIgnoreCase(limiter.dimension())) {
        Long userId = UserHolder.getUser() == null ? 0L : UserHolder.getUser().getId();   // 未登录兜底 0：匿名请求共用一个桶
        return base + ":user:" + userId;                  // 例：seckill:user:1001
    }
    return base;                                          // 全局维度：只用前缀
}
```

**4.4 补充：为什么用 `@Around` 而不是 `@Before` / `@After`**

Spring AOP 有五种通知，限流这套逻辑只有 `@Around` 能完整实现：

| 通知                | 执行时机          | 能阻止目标方法执行吗            | 能改返回值吗               |
| ----------------- | ------------- | --------------------- | -------------------- |
| `@Before`         | 目标方法前         | 不能，只能**抛异常**打断        | 不能                   |
| `@AfterReturning` | 正常返回后         | 已经执行完了                | 不能，只能读结果             |
| `@AfterThrowing`  | 抛异常后          | 已经抛了                  | 不能                   |
| `@After`          | 最后（像 finally） | 不能                    | 不能                   |
| `@Around`         | 包住全过程         | **能**（不调 `proceed()`） | **能**（return 什么就是什么） |

限流的两个硬需求都只有 `@Around` 能满足：

1. **拒绝时要跳过业务方法**——`if` 分支直接 `return`，根本不做 `proceed()`；
2. **拒绝时要返回 `Result.fail` 给前端**——把切面的返回值替换掉。

如果换成 `@Before`，只能靠抛异常打断，还得再写 `@ExceptionHandler` 把异常翻译成 `Result.fail`：

```java
@Before("@annotation(com.hmdp.annotation.RateLimiter)")
public void before(JoinPoint jp) {
    Long allowed = redis.execute(...);
    if (allowed == null || allowed == 0L) {
        throw new RateLimitException("操作过于频繁");   // 唯一打断手段：抛异常
    }
}
// 能实现，但「限流判定 + 打断 + 造返回体」被拆到两个地方，链更长
```

多个通知同时存在时的执行顺序：

```text
@Around（proceed() 之前的代码）
  → @Before
    → 目标方法（Controller）
  → @AfterReturning（正常返回）或 @AfterThrowing（抛异常）
  → @After（无论成败都执行）
@Around（proceed() 之后的代码，如果没被异常打断）
```

两个补充：

- 项目里只有这一个切面（全局搜 `@Aspect` 只有 `RateLimitAspect`，一个 `@Around`）；
- `@Transactional` 底层也是 AOP 的 around 式通知（`TransactionInterceptor`）：开启事务 → proceed 执行业务 → 提交/回滚，和限流切面是同一个套路。

**4.5 `clientIp()`：IP 维度时，IP 从哪来**（`RateLimitAspect.java:68-82`）

```java
private String clientIp() {
    ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
        return "unknown";
    }
    HttpServletRequest request = attributes.getRequest();
    String ip = request.getHeader("X-Forwarded-For");     // 经过代理：代理链写在头里
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
        ip = request.getRemoteAddr();                     // 没经过代理：直连地址
    } else {
        ip = ip.split(",")[0].trim();                     // 多级代理：第一段才是客户端 IP
    }
    return ip;
}
```

两个容易漏的点：

- `X-Forwarded-For` 是客户端可伪造的头：本实现直接信任第一段，只适合「前面有受控网关 / Nginx 重写该头」的部署；裸奔部署下爬虫改头即可绕过限流（边界表已列）；
- `buildKey` 里未登录用户兜底为 `userId = 0L`，所以**所有未登录请求共用 `seckill:user:0` 这一个桶**。秒杀接口本身会先校验登录，影响小；若要防匿名刷，应改用 IP 维度或按 token 计数。

#### 5. 边界（面试追问）

| 追问                      | 回答                                                                                                            |
| ----------------------- | ------------------------------------------------------------------------------------------------------------- |
| 为什么不用 Sentinel 或只在网关限流？ | 网关做粗粒度全局保护；用户和券维度更适合业务层。生产可以两层并用                                                                              |
| 按 IP 限流有什么坑？            | `X-Forwarded-For` 可伪造、NAT 下多用户共用 IP；只能信任受控网关写入的头                                                              |
| Redis 挂了放行还是拒绝？         | 当前 Fail Closed（返回 0/异常都拒绝），偏向保护秒杀；生产可按接口分级降级                                                                  |
| ZSet 方案的代价？             | 每个请求占一个成员，高 QPS 高基数下内存和 O(logN) 成本明显，可换分桶计数 / 网关本地限流                                                          |
| member 为什么拼随机数？         | member 内容不重要、**唯一性**才重要：唯一才能让每次请求各占一个元素、`zcard` 数得准；固定 member 会让 `zcard` 永远为 1，裸时间戳会因同毫秒覆盖少计数。更稳妥是应用传入请求唯一 ID |
| 有性能数据吗？                 | 没有完整的限流前后压测数据，简历只写「降低风险」，不写提升百分比                                                                              |

---


## 二、异步削峰与订单状态控制

> 简历第 2 条拆成两问：**2.1 异步削峰**（RocketMQ 解耦 + 三重幂等）解决「瞬时洪峰 + 重复消费重复下单」；**2.2 订单状态并发控制**（延迟消息超时关单 + 乐观锁）解决「支付回调与关单的并发竞争」。一个是把请求挡在库外，一个是把状态管住。

### 2.1 异步削峰与消息幂等：RocketMQ 解耦 + 三重幂等

*对应简历第 2 条 · 异步削峰部分（原"第二问"）*

> **简历原文**：引入 RocketMQ 将秒杀请求与订单落库流程解耦，通过订单 ID 幂等、用户与优惠券联合校验以及数据库事务保证重复消费不重复创建订单。

#### 面试话术（四段式：背景 → 剖析 → 构思 → 复盘，可直接背）

**① 背景阐述**：上一个优化点解决了"怎么快速判断用户有没有下单资格"，但抢到资格之后，还需要真正生成订单、扣减数据库库存。如果这些操作还放在接口里同步做，接口响应时间会被数据库写操作拖长，秒杀高并发场景下吞吐上不去。

**② 问题剖析**：所以核心思路是把"下单请求"和"订单落库"解耦，用消息队列把请求先接下来，异步慢慢落库。但引入异步之后，新问题就来了：

- 消息队列的投递语义通常是"至少一次"，消息可能因为重试、网络抖动被重复投递；
- 消费端如果直接落库、不做任何判断，同一笔抢购就可能被消费成两笔订单，库存也会重复扣；
- 所以异步方案能不能用，关键在于消费端能不能做到幂等——同一条消息投递多少次，最终结果都必须一样。

**③ 方案构思**：我用 RocketMQ 来做这条异步链路，具体设计：

- 生产端：Lua 返回 0 之后，用 `syncSend` 同步发一条普通消息，显式设置 3 秒超时，确保消息确实被 Broker 接收；发送失败进 catch，执行回滚脚本把 Redis 的预扣补偿回去；
- 消费端落库做三层防重，顺序和代码一致：先用订单 ID 快筛——拿消息里的订单 ID 查库，已存在说明这条消息重投过，直接返回；再拿用户维度的 Redisson 锁；然后在锁内做"用户 + 优惠券"联合校验——查这个用户在这张券上有没有有效订单（`status != 4`，已取消的不算），有就说明同一人重复抢单，直接返回。三层分别防"同一条消息重投、同一个人重复下单、两条消息并发穿过检查"，缺一层都会漏一种场景；
- 通过校验后，先做数据库条件扣减 `update ... set stock = stock - 1 where stock > 0`，影响行数为 0 说明 Redis 和数据库状态对不上，抛异常让消息重试；然后写入订单，整个过程包在一个事务里；
- 订单落库成功后，再发一条延迟消息用于超时关单——这个动作属于下一个优化点，但它是接在这条消费链路后面的；
- 中间件选型上我对比过 Kafka 和 RabbitMQ：Kafka 强在日志/流式大数据吞吐，延迟消息不是原生能力、关单要外挂实现，集群运维也重；RabbitMQ 的延迟消息要装插件；而 RocketMQ 原生支持延迟消息、同步发送能拿到明确确认、部署轻量，Java 生态和中文资料也最匹配，所以选它。

**④ 复盘总结**：异步削峰的本质是"用最终一致性换吞吐"，能不能落地的关键是消费端幂等。我的幂等设计按"代价从低到高"排：订单 ID 查重最便宜；再查用户和券的联合校验；最后用锁兜并发、事务保证原子。再加上生产端的发送失败补偿和后面要讲的定时对账，整条异步链路就是可发现、可修复的。

#### 1. 这条在说什么

秒杀请求被拆成两个线程接力：

- **请求线程**：只做「Redis 预扣 + 把消息交给 RocketMQ」，然后立刻返回 orderId，不等数据库；
- **消费者线程**：收到消息后才真正扣 MySQL 库存、插入订单。

类比：前台接单发号（请求线程），后厨做菜（消费者线程）。前台不等菜做好，人再多号照发；后厨按自己的速度出菜，忙不过来时单子堆在 MQ 里。

「重复消费不重复创建订单」靠消费者里的四层保障实现，见下面第 3 节。

**这条优化的意义**

- **削峰**：秒杀流量是「尖刺」——几千 QPS 集中在几秒内；MQ 把请求线程和落库线程解耦，接口只做「Redis 预扣 + 发消息」就返回，数据库按自己的消费速度处理；
- **解耦**：下单之后还要发券、通知、统计时，不必全部塞进请求线程，下游可以独立扩缩容；
- **没有它会怎样**：
  - 同步落库：峰值直接把数据库连接池打满、接口大面积超时，用户重试又把压力放大；
  - 只异步不幂等：RocketMQ 是「至少一次」语义，重投/重试会导致同一用户多笔订单；
- **本质**：用「订单最终会创建」换「接入层高吞吐」；代价是消息可能重复、延迟、积压，所以必须配幂等（本节）和兜底（第五节）。

#### 2. 完整执行步骤

```mermaid
flowchart TB
    subgraph P["请求线程"]
        A["Redis Lua 预扣成功"] --> B["syncSend 订单消息"] --> C["Broker 确认收到"] --> D["接口返回 orderId"]
    end
    subgraph Q["消费者线程"]
        E["收到订单消息"] --> F["订单 ID 幂等"] --> G["用户锁"] --> H["用户 + 券联合校验<br/>status != 4"] --> I["MySQL 条件扣库存"] --> J["保存订单"]
    end
    C --> E
```

**请求线程**（`VoucherOrderServiceImpl#seckillVoucher`）：

1. Redis Lua 预扣成功（返回 0）；
2. `syncSend` 把订单消息发给 RocketMQ，等 Broker 确认（超时 3000ms）；
3. Broker 确认收到；
4. 接口返回 `orderId`——此时数据库里还没有订单。

发送**明确失败**（`syncSend` 抛异常）时执行补偿，见核心代码 4.2。

**消费者线程**（`SeckillOrderListener#onMessage` → `createVoucherOrder`）：

1. 收到订单消息（JSON，含 orderId/userId/voucherId）；
2. 订单 ID 幂等：`getById(orderId)` 已有 → 重复消息，直接结束；
3. 用户锁：`tryLock lock:order:{userId}`，同一用户的消息串行执行；
4. 用户 + 券联合校验：查 `user_id + voucher_id` 且 `status != 4` 已有订单 → 直接结束；
5. MySQL 条件扣库存：`stock > 0` 才扣，影响 0 行就抛异常；
6. 保存订单：和扣库存在同一个事务里，要么都成功要么都回滚；
7. 落库成功后发延迟关单消息（见2.2）。

#### 3. 为什么重复消费不会重复创建订单（对应简历原话）

| 简历里的词        | 实现                                                | 防的是哪种重复                      |
| ------------ | ------------------------------------------------- | ---------------------------- |
| 订单 ID 幂等     | `getById(orderId) != null` 直接返回                   | 同一条消息被投递多次                   |
| 用户 + 优惠券联合校验 | `count(userId + voucherId，status != 4)` 大于 0 直接返回 | 不同订单 ID、但同一用户同一张券（已取消订单不算重复） |
| 数据库事务        | `@Transactional` 包住「扣库存 + 插订单」                    | 写一半失败导致的库存/订单不一致             |
| 用户锁（配套）      | Redisson `lock:order:{userId}`                    | 同一用户的消息并发穿过上面两个检查            |

流程演示（券 1001，用户 2，消息被投递两次）：

- 第 1 次：订单 ID 不存在 → 扣库存 → 插订单 → 提交；
- 第 2 次：订单 ID 已存在 → 直接返回，不会插第二笔。

**1.1已经用 `SISMEMBER` 查过重了，为什么这里还要再查？**

因为两者**查的对象、阶段、判据都不同**，不能互相替代：

|       | 1.1：入口查重                                                                           | 2.1：落库查重                                                                                                                         |
| ----- | ---------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| 位置    | `src/main/resources/seckill.lua`（请求线程，`VoucherOrderServiceImpl#seckillVoucher` 执行） | `com.hmdp.service.impl.VoucherOrderServiceImpl#createVoucherOrder`（消费线程，由 `com.hmdp.listener.SeckillOrderListener#onMessage` 调用） |
| 判据    | Redis `seckill:order:{voucherId}` 这个 Set                                           | MySQL：主键 `id` + 业务键 `user_id + voucher_id`                                                                                       |
| 拦住什么  | 还没下单的重复请求（用户重复点击 / 脚本重放）                                                           | 已经变成事实的重复落库（消息重复、业务重复）                                                                                                           |
| 去掉会怎样 | 重复请求都能拿到 orderId，Redis 库存被扣多次，可能超卖                                                 | 重复 INSERT 主键冲突导致无限重试，或产生第二笔订单                                                                                                    |

为什么入口查过了还不信？因为**消息入队之后，Redis 状态随时可能被改变**：

```text
补偿 / 释放（同一个 seckill_rollback.lua）：把用户从名单移除
Redis 重启 / 人工操作：名单和流水可能丢失
MQ：同一条消息可能重复投递
```

所以消费端只能信「消息内容 + 数据库事实」。

消费端这三道防守各自的职责（完整类#方法 + 行号）：

| 消费端检查                                     | 位置                                                                                                     | 防的是哪种重复                           | 去掉的后果                   |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------ | --------------------------------- | ----------------------- |
| `getById(orderId)`                        | `com.hmdp.service.impl.VoucherOrderServiceImpl#createVoucherOrder`（`VoucherOrderServiceImpl.java:126`） | 同一条消息被投递多次                        | 重复 INSERT 主键冲突 → 消息无限重试 |
| `count(userId + voucherId + status != 4)` | 同上（`VoucherOrderServiceImpl.java:138-142`）                                                             | 不同订单 ID、同一用户同一券（假失败重抢、对账误用新 ID 等） | 可能产生第二笔订单               |
| Redisson 用户锁（配套）                          | 同上（`VoucherOrderServiceImpl.java:132`）                                                                 | 上面两个“先查再插”之间的并发窗口                 | 两个线程都查不到订单 → 都插入 → 双单   |

**这三层各防什么？先看“密码本”，再看三个故事**

| 层       | 查的东西   | 拦的“重复”               |
| ------- | ------ | -------------------- |
| ① 订单 ID | “这条消息” | 同一条消息被 MQ 投了第二次      |
| ② 用户+券  | “这个人”  | 同一个人换了一条消息又来下第二单     |
| ③ 用户锁   | “同一瞬间” | 两条消息同时到，抢在彼此前面都通过了检查 |

关键常识：**一条消息 = 一个订单号**。同一个人两次下单，订单号一定不一样（订单号是每笔下单时新生成的）。

**故事一：为什么需要 ①（订单 ID）？——老订单可能已经“已取消”**

MQ 是“至少一次”投递，同一条消息可能被投两次。假设消息 A：

```text
1. 第一次消费：成功插入订单 A
2. 消费端还没来得及回执（ack），程序重启了
3. MQ 没收到回执 → 过一会儿把消息 A 重新投一遍
4. 而这段时间里，订单 A 因超时被自动关单了（status=4，库存已退回）
```

现在重投的消息 A 进来了：

- ② 查“用户2 + 券1001 有没有**有效**订单”——没有（A 已取消，被 `ne(status,4)` 排除）→ **② 放行！**
- 如果没有 ①：流程继续 → 又扣一次库存 → 又插订单 A → **主键冲突**（A 的 ID 已存在）→ 报错 → MQ 再重试 → 死循环；
- 有 ①：`getById(A)` 一看“这笔订单存在过” → 直接 return，干净利落。

所以 ① 防的是**消息级**重复，特别是“订单已取消、消息还在重投”这种 ② 看不见的情况。

**故事二：为什么需要 ②（用户+券）？——两条消息的订单号不一样**

```text
1. 用户 2 抢到订单 A（待支付）
2. 超时关单：A 变 status=4，库存和抢购资格都释放了
3. 用户 2 重新抢 —— 这是合法的！产生新订单 B（新订单号）
4. 但假设 Redis 的“已抢名单”被误删（或修复数据时多插了一条消息）
   → 用户 2 又抢了一次，产生订单 C
```

现在同时存在 B、C 两笔有效订单：

- ① 按订单 ID 查：B、C 订单号本来就不同，① 什么都查不出来 → 放行；
- ② 查“用户2+券1001 有没有有效订单”：查到 B 还在（status≠4）→ 直接 return，C 不落库。

**这也是为什么 ② 必须写 `status != 4`**：不排除已取消订单的话，用户“取消后合法重抢”会被 ② 误杀，新订单永远生不出来。所以 ② 防的是**业务级**重复：同一个人、同一张券，只能有一笔有效订单。

**故事三：为什么需要 ③（锁）？——“先查后写”中间有时间差**

假设没有锁，两条不同订单号的消息 B、C 同时被两个消费线程处理：

```text
时刻1   T1：查用户2的有效订单 → 没有
时刻2   T2：查用户2的有效订单 → 没有     ← T1 还没来得及插入
时刻3   T1：插入订单 B，提交
时刻4   T2：插入订单 C，提交
结果： B、C 都是有效订单 → 一人两单
```

问题出在：**“查”的时候都还没有东西，“写”却都发生在查之后**。①② 本身没错，但它们防不住“同时”。

③ 锁的 key 是 `lock:order:{userId}`：同一个用户的消息必须排队——T1 拿锁 → 查+写+提交 → 释放 → T2 才拿锁 → 这时再查 → 查到 B 了 → return。

> 注意：③ 不是在“再查一遍”，而是把“查 + 写”变成一个**不可插队**的整体。这就是代码里 ② 必须写在锁里面的原因——锁外查等于白查。

**一串口诀带走**

- ① 看**票根编号**：这条消息是不是已经用过了？
- ② 看**领奖的人**：这个人手上是不是已经有一张有效券了？
- ③ 让**窗口一次只服务一个人**：同一个人的消息排队处理，别同时办。

**正确执行顺序（与代码一一对应，注意 ② 在锁里面）**

```java
if (getById(voucherOrder.getId()) != null) return;    // ① :125  无锁快筛（PK 查询最便宜）
RLock lock = redissonClient.getLock("lock:order:" + userId);   // ③ :131
lock.tryLock();
try {
    count(userId + voucherId + status != 4);          // ② :137-140  必须在锁内查，锁外查等于没查
    update stock = stock - 1 where stock > 0;         // ④ :147-152
    save(order);                                      // ⑤ :156
} finally { lock.unlock(); }
```

**为什么不用数据库唯一索引一步到位？**

因为业务允许“取消后重新抢”：同一对 `(user_id, voucher_id)` 会先后合法存在多条记录（status=4 的旧单 + 新单），普通唯一索引会被历史取消单卡死；MySQL 又没有“部分唯一索引”（只约束 status != 4 的行）。所以要么用生成列做条件唯一键，要么就用现在的“锁 + 条件查重”，项目选了后者。

一句话总结：**入口 `SISMEMBER` 查 Redis，拦“还没下单的重复请求”（保护库存）；消费端两处查重查 MySQL，拦“已经变成事实的重复落库”（保护订单）。**

边界：`count(userId + voucherId)` 目前是应用层查重，数据库还没有 `(user_id, voucher_id)` 唯一索引；被追问“能不能绝对保证不重复”时，答案是补唯一索引作为最后一道防线。

**为什么 count 必须排除已取消订单（status != 4）**：关单时会执行 release 脚本，把用户从 Redis 名单移除、库存还回去——用户**允许重新抢**。如果 count 把已取消订单也算重复，就会出现这样的僵尸状态：

```text
T1 用户2 抢券 → 订单A（待支付）
T2 超时关单 → 订单A 变 status=4，MySQL/Redis 库存都还回去，名单移除用户2
T3 用户2 重新抢 → 入口 Lua 放行，生成新订单号 B，发消息
T4 消费者处理 B：getById(B)=null；count(用户2+券1001)=1（查到已取消的订单A）
   → 直接 return，订单 B 永远不落库
T5 对账任务每 60 秒重投 B → 消费者每次都 return → 重投不报错、永不收敛
```

所以 `count` 加上 `.ne(VoucherOrder::getStatus, 4)`，与关单释放的语义保持一致（对账的数量校验 `reconcileActiveOrderCounts` 本来也是排除 status=4 的写法）。

#### 4. 核心代码

**4.1 生产者：预扣成功后发消息**（`VoucherOrderServiceImpl.java:103-120`）

```java
VoucherOrder order = new VoucherOrder()
        .setId(orderId)                    // 用预生成的订单 ID（消费者幂等就靠它）
        .setUserId(user.getId())
        .setVoucherId(voucherId);
try {
    // 同步发送：等 Broker 确认；超时 3000ms。确认失败会抛异常，进入 catch 补偿
    rocketMQTemplate.syncSend(orderTopic, JSONUtil.toJsonStr(order), 3000);
} catch (Exception e) {
    // 补偿：回滚 Redis 预扣（这段就是2.1 的 4.2 讲的补偿，直接写全）
    Long compensated = stringRedisTemplate.execute(
            ROLLBACK_SCRIPT,                                       // seckill_rollback.lua
            Arrays.asList(
                    RedisConstants.SECKILL_STOCK_KEY + voucherId,   // KEYS[1] 库存 Key
                    RedisConstants.SECKILL_ORDER_KEY + voucherId,   // KEYS[2] 名单 Key
                    reservationKey(voucherId, user.getId())         // KEYS[3] 流水 Key
            ),
            user.getId().toString()                                 // ARGV[1] 用户 ID
    );
    log.error("RocketMQ 发送失败，Redis 预扣补偿 orderId={}, compensated={}", orderId, compensated, e);
    return Result.fail("排队失败，请重试");                           // 消息没进 Broker，接口返回失败
}
return Result.ok(orderId);                 // 返回订单 ID；此时数据库还没有订单
```

**4.1 补充：syncSend 是什么，还有其他发送方式吗**

`syncSend` = 同步发送：把消息发给 Broker 后，当前线程**阻塞等 Broker 返回确认**，确认成功才继续；超时或失败抛异常。本项目用它就是为了明确知道「消息到底进没进 Broker」，从而决定是继续返回成功，还是回滚 Redis 走补偿。

RocketMQTemplate 的其他发送方法：

| 方法                         | 是否等 Broker 确认 | 能否感知失败                 | 适合场景                     |
| -------------------------- | ------------- | ---------------------- | ------------------------ |
| `syncSend`                 | 等（阻塞线程）       | 能，失败抛异常                | 本项目：预扣后必须知道消息有没有进 Broker |
| `asyncSend`                | 不等，回调通知       | 能，在 `SendCallback` 里处理 | 高吞吐场景；但补偿逻辑要搬进回调，代码复杂    |
| `sendOneWay`               | 完全不等          | 不能                     | 日志等丢了无所谓的消息              |
| `sendMessageInTransaction` | ——            | 支持 Broker 回查本地事务       | 本地数据库事务与消息的一致性（本项目没用）    |

另外还有 `syncSendOrderly` / `asyncSendOrderly` / `sendOneWayOrderly` 顺序消息版本：按同一个 hashKey 把消息发到同一个队列保证顺序，本项目不需要。

项目里用到的两个重载：

```java
// ① 普通消息：topic、消息体、超时时间(ms)
rocketMQTemplate.syncSend(orderTopic, JSONUtil.toJsonStr(order), 3000);

// ② 延迟消息：多一个延迟等级参数（level 5 = 1 分钟）
rocketMQTemplate.syncSend(timeoutTopic, message, 3000, timeoutDelayLevel);
```

**4.2 发送失败的补偿**（= 2.1 的 4.1 的 catch 块，单独拆出来讲）（`VoucherOrderServiceImpl.java:106-118` + `seckill_rollback.lua`）

```java
} catch (Exception e) {
    Long compensated = stringRedisTemplate.execute(
            ROLLBACK_SCRIPT,                                       // seckill_rollback.lua
            Arrays.asList(
                    RedisConstants.SECKILL_STOCK_KEY + voucherId,   // KEYS[1] 库存 Key
                    RedisConstants.SECKILL_ORDER_KEY + voucherId,   // KEYS[2] 名单 Key
                    reservationKey(voucherId, user.getId())         // KEYS[3] 流水 Key
            ),
            user.getId().toString()                                 // ARGV[1] 用户 ID
    );
    log.error("RocketMQ 发送失败，Redis 预扣补偿 orderId={}, compensated={}", orderId, compensated, e);
    return Result.fail("排队失败，请重试");                           // 消息没进 Broker，接口返回失败
}
```

```lua
-- seckill_rollback.lua：回滚预扣
-- KEYS[1]=库存  KEYS[2]=名单  KEYS[3]=流水  ARGV[1]=用户 ID
if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then   -- 先判断：用户还在名单里吗？
    redis.call('srem', KEYS[2], ARGV[1])     -- 移出名单（允许重新抢）
    redis.call('incrby', KEYS[1], 1)         -- 库存 +1（还回去）
    redis.call('del', KEYS[3])               -- 删流水
    return 1                                 -- 1 = 本次真的回滚了
end
redis.call('del', KEYS[3])                   -- 已被其他流程释放过：只清流水
return 0                                     -- 0 = 没做任何回滚，防止库存多加
```

补偿脚本返回值：**1 = 本次真的回滚了；0 = 资格已不在，没做任何回滚**。先判断再回滚是为了重复执行不会导致库存多加。

**4.3 消费者：订单消息监听**（`SeckillOrderListener.java:23-52`）

```java
@RocketMQMessageListener(                     // 声明这是 RocketMQ 消费者，框架自动启动消费线程
        topic = "${seckill.rocketmq.order-topic:seckill-order-topic}",                  // 监听下单消息
        consumerGroup = "${seckill.rocketmq.order-consumer-group:seckill-order-consumer}", // 消费组
        consumeMode = ConsumeMode.CONCURRENTLY,   // 并发消费（多线程处理消息）
        messageModel = MessageModel.CLUSTERING    // 集群模式：同组内一条消息只被一个实例消费
)
public class SeckillOrderListener implements RocketMQListener<String> {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(String message) {                      // MQ 收到消息后回调这里
        VoucherOrder order = JSONUtil.toBean(message, VoucherOrder.class);   // JSON 反序列化成订单对象
        voucherOrderService.createVoucherOrder(order);           // 事务落库；抛异常 → MQ 自动重试
        // 落库成功后发延迟关单消息（见2.2）
    }
}
```

**4.3 补充：onMessage 为什么会被“自动”调用**

靠的是「注解 + 接口回调（多态）」两件事配合：

- `@RocketMQMessageListener`：负责“接线”。Spring 启动时扫描到这个注解，为类创建 `DefaultMQPushConsumer`，订阅 topic、绑定消费组，并把监听器对象注册为消息回调；
- `implements RocketMQListener<String>`：负责“被调用时执行什么”。接口只约定一个方法：

```java
public interface RocketMQListener<T> {
    void onMessage(T message);
}
```

你的类实现这个接口，相当于告诉框架：

> 我是一个消息处理器。你收到消息后，可以调用我的 `onMessage()`。

这里用的是 Java 的多态和接口回调。

框架内部持有的是**接口类型**的引用：

```java
private RocketMQListener<?> rocketMQListener;   // 框架只认识接口，不认识你的类
// 消费线程池收到消息后（简化）：
rocketMQListener.onMessage(payload);            // 多态：运行期绑定到 SeckillOrderListener.onMessage
```

完整的启动与回调链路：

```text
Spring Boot 启动
  → RocketMQAutoConfiguration 自动装配（读 rocketmq.name-server）
  → ListenerContainerConfiguration 扫描 @RocketMQMessageListener 的 Bean
  → 为每个监听器创建 DefaultRocketMQListenerContainer
  → 容器创建并启动 DefaultMQPushConsumer（subscribe topic + registerMessageListener）
  → 客户端后台线程工作：PullMessageService 拉消息 / ConsumeMessageService 线程池消费
  → 适配器把消息转成 String，调用 rocketMQListener.onMessage(message)
  → 正常返回 = ACK；抛异常 = 失败重试
```

一句话：**注解负责“被发现和注册”，接口 + 多态负责“消息到来时执行哪段代码”**。这也和 `@KafkaListener`、Servlet 的 `doGet`、线程的 `run()` 是同一个思想：你实现接口，框架在合适的时机回调你。

**4.4 消费者：四层幂等 + 事务落库**（`VoucherOrderServiceImpl.java:125-162`，全文）

MQ 至少一次投递，必须去重

```java
/** RocketMQ 消费端事务：订单 ID 幂等 + 用户/券幂等 + 数据库条件扣库存。 */
@Override
@Transactional(rollbackFor = Exception.class)                // 整个方法一个事务
public void createVoucherOrder(VoucherOrder voucherOrder) {

    // ① 订单 ID 幂等：同一条消息重复投递
    if (getById(voucherOrder.getId()) != null) {     // 等价 SQL：SELECT * FROM tb_voucher_order WHERE id = orderId
        log.info("重复消费订单消息，按订单 ID 幂等返回 orderId={}", voucherOrder.getId());
        return;
    }

    Long userId = voucherOrder.getUserId();

    // ② 用户锁：同一用户的多条消息并发时串行
    // 没传 leaseTime，watchdog（看门狗）是默认开启的。
    // 把「同一个用户」的多条消息串行化，堵住两次查重之间的并发窗口
    // 
    RLock lock = redissonClient.getLock("lock:order:" + userId);   // Key：lock:order:1001
    boolean locked = lock.tryLock();                 // 拿不到锁不等待，直接失败
    if (!locked) {
        throw new IllegalStateException("用户订单并发锁获取失败");      // 抛异常 → MQ 重试
    }
    try {
        // ③ 用户 + 券联合校验：不同订单 ID、同一用户同一张券（查订单表 tb_voucher_order）
        long duplicated = count(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getUserId, userId)                       // WHERE user_id = ?
                .eq(VoucherOrder::getVoucherId, voucherOrder.getVoucherId())  // AND voucher_id = ?
                .ne(VoucherOrder::getStatus, 4));                          // AND status != 4：已取消订单不算重复
        // 等价 SQL：SELECT COUNT(*) FROM tb_voucher_order
        //           WHERE user_id = ? AND voucher_id = ? AND status != 4
        if (duplicated > 0) {
            log.info("重复消费用户券消息，按用户和券幂等返回 userId={}, voucherId={}",
                    userId, voucherOrder.getVoucherId());
            return;                                   // 已有订单，不再插入
        }

        // ④ 条件扣库存（设计说明在1.1 的 4.3，实际执行就是2.1这里）——操作的是秒杀券表 tb_seckill_voucher
        boolean stockUpdated = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                        .gt(SeckillVoucher::getStock, 0)
                        .setSql("stock = stock - 1")
        );
        // 等价 SQL：UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = ? AND stock > 0
        if (!stockUpdated) {
            throw new IllegalStateException("数据库库存与 Redis 预扣状态不一致");   // 回滚 + 重试
        }

        // ⑤ 插入订单：和扣库存在同一个事务
        save(voucherOrder);                           // 等价 SQL：INSERT INTO tb_voucher_order (id, user_id, voucher_id, status, ...) VALUES (...)
    } finally {
        lock.unlock();                                // 无论成功失败都释放锁
    }
}
```

**4.4 补充：①③④⑤ 分别操作哪张表（看不懂表名看这里）**

MyBatis-Plus 靠实体类上的 `@TableName` 注解做表映射，所以代码里看不到表名：

```text
VoucherOrder   → @TableName("tb_voucher_order")      订单表
SeckillVoucher → @TableName("tb_seckill_voucher")    秒杀券库存表
```

| 步骤  | 代码方法                                | 操作的表               | 等价 SQL                                                                               | 目的               |
| --- | ----------------------------------- | ------------------ | ------------------------------------------------------------------------------------ | ---------------- |
| ①   | `getById(orderId)`                  | tb_voucher_order   | `SELECT * FROM tb_voucher_order WHERE id = ?`                                        | 这条消息的订单是否已存在     |
| ③   | `count(...)`                        | tb_voucher_order   | `SELECT COUNT(*) FROM tb_voucher_order WHERE user_id = ? AND voucher_id = ?`         | 该用户对该券是否已有订单     |
| ④   | `seckillVoucherService.update(...)` | tb_seckill_voucher | `UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = ? AND stock > 0` | 扣 MySQL 库存（最终事实） |
| ⑤   | `save(order)`                       | tb_voucher_order   | `INSERT INTO tb_voucher_order (id, user_id, voucher_id, ...) VALUES (...)`           | 插入订单             |

两个“库存”不要搞混：

- Redis 的 `seckill:stock:1001`：Lua 预扣时已经扣过（1.1）；
- MySQL 的 `tb_seckill_voucher.stock`：到 ④ 这里才扣，是持久化事实和兜底。

#### 5. 消息说明（普通 / 延迟 / 事务）

| 消息   | 类型                       | 发送方式              | 位置                                   |
| ---- | ------------------------ | ----------------- | ------------------------------------ |
| 下单消息 | 普通消息                     | `syncSend`（等确认）   | `VoucherOrderServiceImpl.java:105`   |
| 关单消息 | **延迟消息**（level 5 = 1 分钟） | `syncSend` + 延迟等级 | `SeckillOrderListener.java:45`       |
| 对账重投 | 普通消息                     | `syncSend`        | `SeckillReconciliationTask.java:130` |

**没有使用事务消息**：事务消息需要可查询的本地事务状态来支持 Broker 回查，而秒杀入口首先改的是 Redis，换 API 解决不了 Redis 与消息的一致性；这里用「同步确认 + 失败补偿 + 对账重投」替代。

#### 6. 为什么选 RocketMQ（Kafka / RabbitMQ 对比）

先列清楚这个项目对消息中间件的硬需求，再对比：

| 需求         | 说明                                          |
| ---------- | ------------------------------------------- |
| 异步削峰       | 秒杀尖刺流量，MQ 要稳定承接、按自己的速度消费                    |
| 同步发送确认     | 必须能 `syncSend` 等 Broker 明确回执，发送失败才能立刻执行回滚补偿 |
| 延迟消息       | 超时关单依赖「下单后 1 分钟触发」，最好原生支持                   |
| 至少一次 + 可重试 | 消费失败要能重试，配合消费端幂等保证不重复下单                     |
| 部署轻量       | 本地/单机就能跑（namesrv + broker 两个进程），不引入过重运维     |

三个候选的对比：

| 维度     | **RocketMQ（选）**                              | Kafka                           | RabbitMQ                                               |
| ------ | -------------------------------------------- | ------------------------------- | ------------------------------------------------------ |
| 延迟消息   | **原生支持**：固定延迟等级（1s ~ 2h，本项目用 level 5 = 1 分钟） | 不支持，需外挂自研时间轮 / Kafka Streams 变通 | 需插件 `rabbitmq_delayed_message_exchange`，或 DLX + TTL 变通 |
| 发送结果确认 | `syncSend` 同步等 ACK，失败可立即补偿                   | 有 acks 和回调，但模型偏批量/异步            | publisher confirm 支持                                   |
| 吞吐定位   | 十万级，电商/交易场景为典型                               | 百万级，日志/流式大数据                    | 万级，业务解耦                                                |
| 消息特性   | 延迟、重试、死信、事务消息、消息回溯齐全                         | 分区 / offset / 顺序 / 回溯强          | 路由模型灵活、生态成熟                                            |
| 运维复杂度  | 中：namesrv + broker，纯 Java 系                  | 高：集群 + ZK/KRaft，参数多             | 中：Erlang 运行时 + 插件管理                                    |
| 资料/生态  | 阿里出品，中文资料多，国内团队熟                             | 社区大但偏大数据方向                      | 社区大，国内业务向资料相对少                                         |

**结论：不是 Kafka / RabbitMQ "不行"，是场景不匹配：**

- **Kafka**：强项是日志采集/流处理这种超高吞吐场景，本项目的消息量级远没到那个规模；延迟消息不是原生能力，关单要额外实现；集群运维成本高——属于大炮打蚊子；
- **RabbitMQ**：消息可靠性和路由能力很好，但延迟消息要靠插件，用法和精度不如 RocketMQ 顺；Erlang 技术栈的运维成本也偏高；
- **RocketMQ**：上面五个硬需求全部命中——原生延迟消息正好做超时关单、`syncSend` 让"发送失败补偿"链路最顺、单机两个进程就能跑、Java 生态和中文资料最匹配；它在电商/秒杀场景是事实标准，稳定性和吞吐足够支撑这条链路。

**追问：延迟等级不够精确怎么办？** RocketMQ 4.x 的延迟消息是 18 个固定等级（1s 到 2h），不支持任意秒数；5.x 才支持任意定时。我们这个项目只需要"下单后 1 分钟关单"这种粗粒度场景，等级完全够用；如果未来要任意精度，可以换 Redisson 的 RDelayedQueue 或自建时间轮调度。

#### 7. 边界（面试追问）

| 追问                                  | 回答                                                                                                                                                   |
| ----------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| 接口返回成功 = 订单已生成？                     | 不是。只代表预扣成功 + 消息进了 Broker，订单由消费者异步落库                                                                                                                  |
| 消息怎么不丢？                             | 同步等确认 + 明确失败补偿 + 消费异常重试 + 对账重投                                                                                                                       |
| 消息积压怎么办？                            | 消息停在 Broker，接口照常返回；恢复后消费者追平。处理积压先查消费者异常/慢 SQL/连接池，再考虑扩消费者                                                                                            |
| 重复消费怎么保证不重复下单？                      | 订单 ID + 用户锁 + 用户/券校验 + 事务，见第 3 节表格                                                                                                                   |
| 为什么用 RocketMQ，Kafka / RabbitMQ 不行吗？ | 见第 6 节。一句话：本项目要的是「延迟消息 + 同步发送确认 + 轻量部署」，RocketMQ 原生全有；Kafka 延迟消息要外挂、偏日志大数据场景，RabbitMQ 延迟要装插件                                                         |
| Redisson 锁在事务提交前就释放，算不算漏洞？          | 严格说存在毫秒级窗口：`finally` 解锁发生在 Spring 事务提交之前，下一个线程理论上可能查不到未提交的订单。更严密的做法：解锁挪到 `TransactionSynchronization#afterCommit`，或补 `(user_id, voucher_id)` 条件唯一键兜底 |

---

### 2.2 订单状态并发控制：延迟消息超时关单 + 乐观锁

*对应简历第 2 条 · 订单状态部分（原"第四问"）*

> **简历原文**：使用 RocketMQ 延迟消息实现超时关单，基于乐观锁思想控制订单状态流转，解决支付回调与超时关单之间的并发竞争，并在关单后回补库存，保证订单与库存状态一致性。

#### 面试话术（四段式：背景 → 剖析 → 构思 → 复盘，可直接背）

**① 背景阐述**：用户抢到券之后会生成订单，订单有完整的状态流转：1 是待支付，2 是已支付，4 是已取消。业务上要求：用户超时没支付，订单要自动关闭，并且把之前扣掉的库存补回去。

**② 问题剖析**：这个场景里有两个容易被忽略的并发问题：

- 第一个是"支付回调"和"超时关单"可能同时发生。比如用户恰好在关单程序执行的那一瞬间完成支付，如果两边都是先查状态再改状态，就可能两个线程都查到"待支付"，然后一个改成已支付、一个改成已取消——订单状态就错了，库存也可能被重复回补；
- 第二个是关单动作本身必须只生效一次。关单要回补库存，如果重复执行库存就会多出来；而且关单的触发源不止一个（延迟消息、用户主动取消、对账补偿），天然会重复触达；
- 再想一步，超时关单怎么触发？如果用定时任务每分钟扫全表"查超时未支付订单"，数据量大时性能差，关单精度最高也就一分钟。更好的是事件驱动——下单时就知道什么时候该关它。

**③ 方案构思**：我的解法分三块：

- 第一块，状态流转全部用"条件更新"的乐观锁思路仲裁并发。这里我对比过两种方案：悲观思路的"一锁二判三更新"是先拿分布式锁锁住订单、再判断状态、再更新；乐观思路是让两条 UPDATE 各自带上 `and status = 1` 条件——支付回调执行 `update ... set status = 2 where id = ? and status = 1`，超时关单执行 `set status = 4 where id = ? and status = 1`，只有"待支付"能被改成目标状态，谁先成功谁生效，后到的影响行数为 0、直接放弃。我选乐观锁有两个原因：一是支付和关单撞车本来是小概率事件，乐观锁正适合冲突少的场景；二是关单是批量任务，一次可能处理大量超时订单，逐单抢分布式锁的性能开销太大。严格说我们没加 version 字段，靠订单状态本身当条件，本质是 CAS 思想；
- 竞争的结果只有两种：支付赢 → 关单影响 0 行、放弃且**不回补库存**，业务正常；关单赢 → 支付回调影响 0 行、状态保持已取消，但用户可能已经付了钱，业务上要触发**原路退款**（本项目暂未实现，属于生产要补的流程）；
- 第二块，超时触发用 RocketMQ 延迟消息：订单落库成功后，消费端再发一条延迟消息到关单主题，时间到自动触发关单接口。事件驱动不用扫表，精度由消息中间件保证；
- 第三块，关单成功后的库存回补：MySQL 侧执行 `stock = stock + 1`；Redis 侧走和前两个问题共用的回滚 Lua——脚本先用 SISMEMBER 判断用户是否还在抢购名单里，在才执行 SREM、库存 +1、删流水，不在就只删流水。这样无论关单被触发多少次，库存只会回补一次。

**④ 复盘总结**：这个优化点的关键词是"状态机 + 条件更新"。支付和关单的并发竞争不需要锁，让数据库的条件更新来当裁判；超时关单用延迟消息替代扫表，更实时也更省资源；库存回补和资格释放全部做成幂等的。这三件事合起来，订单状态和库存状态就始终是一致的。

#### 先看：订单状态字典与 status 从哪来

**状态字典（来源：实体注释 + 建表注释）：**

| status | 含义       | 谁把它改成这个值                 | 代码里是否实现 |
| ------ | -------- | ------------------------ | ------- |
| **1**  | 未支付（待支付） | 数据库默认值（插入订单时自动为 1）       | 是       |
| **2**  | 已支付      | `payCallback` 条件更新       | 是       |
| **3**  | 已核销      | ——                       | 否，预留    |
| **4**  | 已取消      | `closeTimeoutOrder` 条件更新 | 是       |
| **5**  | 退款中      | ——                       | 否，预留    |
| **6**  | 已退款      | ——                       | 否，预留    |

**status = 1 从哪来？** Java 插入订单时并没有 `setStatus`，靠的是数据库默认值（`hmdp.sql`）：

```sql
`status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1,
`create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
`update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
```

MyBatis-Plus 默认插入策略是 NOT_NULL：实体里为 null 的字段不会放进 INSERT → MySQL 用 `DEFAULT 1` 兜底。
`create_time` / `update_time` 同理由数据库维护；`pay_time` 则由 `payCallback` 显式写入。

**谁在读 status：**

| 位置                  | 读法                  | 作用                      |
| ------------------- | ------------------- | ----------------------- |
| `payCallback`       | `WHERE status = 1`  | 只有待支付能改成已支付             |
| `closeTimeoutOrder` | `WHERE status = 1`  | 只有待支付能改成已取消             |
| 对账数量校验              | `WHERE status != 4` | 只统计未取消订单，与 Redis 名单人数对比 |
| 对账修复分支              | `status == 4`       | 已取消订单触发 Redis 幂等释放      |

**代码里真实存在的迁移只有两条：**

```text
status = 1（待支付，数据库默认）
   ├── payCallback：1 → 2（已支付）
   └── closeTimeoutOrder：1 → 4（已取消）
```

两个迁移都带 `WHERE status = 1`，谁先影响 1 行谁赢——这就是下面要讲的“状态条件更新”。

#### 1. 这条在说什么

订单落库后状态是 1（待支付），有两个出口：

- 支付回调：1 → 2（已支付）；
- 延迟关单：1 → 4（已取消）。

两个操作可能同时到达同一笔订单。如果都先查状态再无条件更新，就可能都读到「待支付」，后执行的覆盖先执行的（已支付订单被关闭、或已关闭订单又被支付）。解法：把旧状态写进 UPDATE 的 WHERE 条件（`WHERE status = 1`），让数据库原子地决定谁赢，输的一方影响 0 行、直接结束。

**这条优化的意义**

- **库存不丢**：未支付订单超时自动关单，并归还占用的库存（Redis +1、MySQL +1），库存形成闭环，不会被僵尸订单永久占用；
- **状态不错**：支付回调与关单并发时，只有一方能把 `status=1` 改走，避免「已支付订单被关闭」或「已关闭订单又被支付」；
- **没有它会怎样**：
  - 没有超时关单：用户抢到不付款，库存被永久占住，后来的人抢不到；
  - 没有状态条件更新（先查再改）：两个操作都读到「待支付」，后写覆盖先写，状态和库存对不上（已关单却又被支付、库存重复回补）；
- **本质**：订单是一个状态机，并发的关键是「用旧状态做条件、让影响行数裁决」，属于 CAS / 乐观并发思想。

#### 2. 超时关单完整执行步骤（示例：订单 1758000000000000001）

```mermaid
flowchart TD
    subgraph LAND["订单落库阶段"]
        A["createVoucherOrder 事务提交<br/>tb_voucher_order.status = 1（待支付）"]
        A --> B["syncSend 延迟消息<br/>order-timeout-topic，delayLevel = 5（1 分钟）"]
    end

    B --> C["1 分钟后 OrderTimeoutListener.onMessage(orderId)"]
    C --> D["closeTimeoutOrder(orderId)（@Transactional）"]

    A -.->|"同一时间窗口内"| P["支付回调 payCallback：<br/>SET status = 2 WHERE id = ? AND status = 1"]
    P -.->|"支付先赢 → 关单影响 0 行"| H

    D --> E{"① getById(orderId)<br/>订单存在吗？"}
    E -->|"不存在"| F["return false"]
    E -->|"存在"| G{"② 条件更新：<br/>SET status = 4<br/>WHERE id = ? AND status = 1"}

    G -->|"影响 0 行<br/>（支付先赢 / 消息重复投递）"| H["return false<br/>不碰任何库存"]
    G -->|"影响 1 行：关单成功"| I["③ MySQL 回补库存：<br/>UPDATE tb_seckill_voucher<br/>SET stock = stock + 1"]

    I -->|"影响 0 行"| J["抛异常<br/>→ 事务回滚，订单退回待支付"]
    I -->|"影响 1 行"| K["④ 执行 seckill_rollback.lua 释放 Redis 预扣"]

    K --> L{"SISMEMBER：<br/>名单里还有这个用户吗？"}
    L -->|"在（没释放过）"| M["SREM 移出名单<br/>INCRBY 库存 +1<br/>DEL 删除流水<br/>返回 1"]
    L -->|"不在（已释放过）"| N["只 DEL 流水<br/>返回 0，不重复加库存"]
    L -->|"脚本异常"| O["返回 null<br/>→ 抛异常，事务回滚"]

    M --> Q["return true → 事务提交"]
    N --> Q

    style D fill:#eef,stroke:#66f
    style K fill:#fee,stroke:#f66
    style P fill:#efe,stroke:#3a3
```

1. 订单在 `createVoucherOrder` 落库成功（status = 1）；

2. `SeckillOrderListener` 紧接着发一条**延迟消息**到 `order-timeout-topic`，`delayLevel = 5`（RocketMQ 4.9.4 的 level 5 = 1 分钟）；

3. 1 分钟后消息到期，`OrderTimeoutListener` 收到订单 ID；

4. 调 `closeTimeoutOrder`：条件更新 `WHERE id = ? AND status = 1` 改成 4；

5. 影响 0 行 → 说明支付回调已经先赢了（status 已变 2），直接返回 false，**不碰库存**；

6. 影响 1 行 → 继续：
   
   - MySQL 秒杀券库存 `stock = stock + 1`；
   
   - 执行 `seckill_rollback.lua` 释放 Redis 预扣；

7. 返回值：`true` = 本次真的关单成功，`false` = 状态已被别人改走。

**2.1 延迟消息为什么“1 分钟后”能自动执行**

“等 1 分钟”的是 Broker，不是生产者或消费者在 sleep：

```text
① 生产者：syncSend(topic, msg, 3000, 5)
   消息体上带属性 delayTimeLevel = 5，立即发到 Broker，方法立刻返回

② Broker：发现有延迟属性，先不投递
   存进内部主题 SCHEDULE_TOPIC_XXXX 对应等级的队列（level 5 → 队列 4）
   记录真实目的地 REAL_TOPIC = order-timeout-topic

③ Broker 的定时任务不断扫描延迟队列：
   到点（存储时间 + 1 分钟 <= 当前时间）→ 取出消息，恢复真实 topic
   → 重新写回 order-timeout-topic

④ 消费者正常长轮询 order-timeout-topic，消息一出现就拉到
   → OrderTimeoutListener.onMessage → closeTimeoutOrder
```

三个要点：

- 延迟期间消息**持久化在 Broker 磁盘**，重启不丢，到点照投；
- 是“至少延迟 1 分钟”：实际投递时间取决于 Broker 定时任务的扫描粒度，所以是“约 1 分钟”；
- RocketMQ 4.x 只有固定 18 档：`1s / 5s / 10s / 30s / 1m / 2m / 3m / 4m / 5m / 6m / 7m / 8m / 9m / 10m / 20m / 30m / 1h / 2h`；要任意时间要靠 5.x 定时消息（`syncSendDelayTimeSeconds`）或自建延迟方案。

#### 3. 支付/关单竞争与库存只释放一次

**竞争演示**：同一订单上 50 个支付请求 + 50 个关单请求交错：

```text
支付回调：UPDATE tb_voucher_order SET status = 2 WHERE id = ? AND status = 1
延迟关单：UPDATE tb_voucher_order SET status = 4 WHERE id = ? AND status = 1
```

两个 SQL 都要求「当前还是待支付」，行锁下只有一个能成功，另一个影响 0 行。

**为什么用乐观锁，而不是「一锁二判三更新」？**

| 方案 | 做法 | 优点 | 代价 |
| --- | --- | --- | --- |
| **乐观锁（选）：状态条件更新** | 两条 UPDATE 都带 `AND status = 1`，用影响行数判断输赢 | 不加锁、天然幂等、适合小概率冲突 | 输的一方要自己处理"失败分支"（放弃 / 退款） |
| 悲观锁：「一锁二判三更新」 | 先分布式锁锁住订单 → 判状态 → 更新 → 解锁 | 逻辑直观，失败方拿不到锁 | 每单都要抢锁释放；关单是批量任务，逐单加锁性能差；还要处理锁超时 |
| version 版本号乐观锁 | 表加 version 字段，`UPDATE ... WHERE version = 旧值` | 通用做法 | 要改表结构；这里的"状态"本身就是天然版本号，不需要额外字段 |

**竞争的结果只有两种，分别怎么处理：**

```text
结果一：支付赢（1 → 2 成功），关单影响 0 行
  → 关单放弃、不回补库存（状态已不是 1，走不到库存回补那步）——业务正常，无需额外处理

结果二：关单赢（1 → 4 成功），支付回调影响 0 行
  → 状态保持已取消，不强行改回已支付（库存和资格都已经还回去了）
  → 但用户可能真的付了钱 → 业务上必须触发「原路退款」（本项目未实现，属于生产要补的流程）
```

**关键理解**：乐观锁解决的是「状态只能被改一次」，它不能替业务决定「钱怎么办」——支付赢了什么都不用做；关单赢了要走退款。这个分工想清楚，面试就不会被问倒。

**库存为什么不重复释放**（两道保险）：

1. MySQL 层面：只有状态 1→4 更新成功才去回补库存，失败的调用到不了库存那一步；
2. Redis 层面：`seckill_rollback.lua` 先 `SISMEMBER` 判断资格还在不在，重复执行第二次直接返回 0。

```lua
-- seckill_rollback.lua
-- KEYS[1]=库存  KEYS[2]=名单  KEYS[3]=流水  ARGV[1]=用户 ID
if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then   -- 名单里还有这个用户 → 没释放过
    redis.call('srem', KEYS[2], ARGV[1])     -- 移出名单（资格还回去）
    redis.call('incrby', KEYS[1], 1)         -- 库存 +1
    redis.call('del', KEYS[3])               -- 删流水
    return 1                                 -- 1 = 本次真正释放
end
redis.call('del', KEYS[3])                   -- 已释放过：只清流水
return 0                                     -- 0 = 不重复加库存
```

返回值：**1 = 本次真正释放；0 = 已被释放过，什么都没加**。

#### 4. 核心代码

**4.1 落库成功后发延迟消息**（`SeckillOrderListener.java` 全文）

```java
/** RocketMQ 秒杀订单消费者：事务落库成功后发送延迟关单消息。 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "seckill.consumer", name = "enabled", havingValue = "true", matchIfMissing = true)
// 消费开关：seckill.consumer.enabled=false 时不注册消费者（压测“积压恢复”时用过）
@RocketMQMessageListener(
        topic = "${seckill.rocketmq.order-topic:seckill-order-topic}",                      // 监听下单 topic
        consumerGroup = "${seckill.rocketmq.order-consumer-group:seckill-order-consumer}",  // 消费组
        consumeMode = ConsumeMode.CONCURRENTLY,    // 并发消费
        messageModel = MessageModel.CLUSTERING     // 集群模式：同组内一条消息只被一个实例消费
)
public class SeckillOrderListener implements RocketMQListener<String> {   // 实现接口 = 注册回调
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Value("${seckill.rocketmq.timeout-topic:order-timeout-topic}")
    private String timeoutTopic;            // 关单 topic
    @Value("${seckill.rocketmq.timeout-delay-level:5}")
    private int timeoutDelayLevel;          // 延迟等级：5 = 1 分钟
    @Override
    public void onMessage(String message) {                      // MQ 收到消息后回调这里
        VoucherOrder order = JSONUtil.toBean(message, VoucherOrder.class);   // JSON → 订单对象
        voucherOrderService.createVoucherOrder(order);           // ① 先落库；失败就抛异常，不会发延迟消息
        rocketMQTemplate.syncSend(                               // ② 落库成功才发延迟消息
                timeoutTopic,
                MessageBuilder.withPayload(String.valueOf(order.getId())).build(),   // 消息体 = 订单 ID
                3000,                                            // 发送超时 3 秒
                timeoutDelayLevel);                              // 第 4 个参数 = 延迟等级
        log.info("RocketMQ 秒杀订单处理完成 orderId={}, voucherId={}", order.getId(), order.getVoucherId());
    }
}
```

**4.2 延迟消息消费**（`OrderTimeoutListener.java` 全文）

```java
/** RocketMQ 延迟消息消费者：未支付订单条件关单并释放一次库存。 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "seckill.consumer", name = "enabled", havingValue = "true", matchIfMissing = true)
// 消费开关：seckill.consumer.enabled=false 时不注册消费者（压测“积压恢复”时用过）
@RocketMQMessageListener(
        topic = "${seckill.rocketmq.timeout-topic:order-timeout-topic}",                      // 监听关单 topic
        consumerGroup = "${seckill.rocketmq.timeout-consumer-group:order-timeout-consumer}",  // 消费组
        consumeMode = ConsumeMode.CONCURRENTLY,    // 并发消费
        messageModel = MessageModel.CLUSTERING     // 集群模式：同组内一条消息只被一个实例消费
)
public class OrderTimeoutListener implements RocketMQListener<String> {   // 实现接口 = 注册回调

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(String orderId) {               // 延迟消息到期后回调，消息体就是订单 ID
        boolean closed = voucherOrderService.closeTimeoutOrder(Long.valueOf(orderId));
        log.info("RocketMQ 延迟关单 orderId={}, closed={}", orderId, closed);
        // closed = true：本次真的关单并释放；false：支付已抢先或消息重复，什么都没做
        // 抛异常 → 框架回失败 → Broker 稍后重投（closeTimeoutOrder 幂等，不会重复释放）
    }
}
```

**4.3 支付回调：条件更新**（`VoucherOrderServiceImpl.java:183-189`）

```java
@Override
@Transactional(rollbackFor = Exception.class)
public boolean payCallback(Long orderId) {
    return update(new LambdaUpdateWrapper<VoucherOrder>()        // 操作的表：tb_voucher_order
            .eq(VoucherOrder::getId, orderId)                    // WHERE id = ?
            .eq(VoucherOrder::getStatus, 1)                      // AND status = 1：旧状态必须是待支付
            .set(VoucherOrder::getStatus, 2)                     // SET status = 2（已支付）
            .set(VoucherOrder::getPayTime, LocalDateTime.now()));// SET pay_time = 当前时间
    // 等价 SQL：UPDATE tb_voucher_order SET status = 2, pay_time = ? WHERE id = ? AND status = 1
    // 返回 true = 迁移成功；false = 状态已被关单改走
}
```

**4.3 补充：支付回调是被谁调用的**

这个接口在项目里**没有任何代码调用它**——它是留给人**手动模拟**的（项目没有接真实支付网关）。

本项目的手动调用方式：

```bash
curl -X POST http://127.0.0.1:8081/voucher-order/pay/callback/1758000000000000001
```

完整链路：

```text
手动请求
→ com.hmdp.controller.VoucherOrderController#payCallback      VoucherOrderController.java:34
→ com.hmdp.service.impl.VoucherOrderServiceImpl#payCallback   VoucherOrderServiceImpl.java:197
→ UPDATE tb_voucher_order SET status = 2, pay_time = ? WHERE id = ? AND status = 1
→ 影响 1 行 → Result.ok()；影响 0 行 → “支付回调处理失败或订单状态已变更”
```

注意：`com.hmdp.config.MvcConfig` 的登录拦截器（`MvcConfig.java:30-37`）只放行了 `/user/**`、`/blog/hot`、`/shop/**`、`/shop-type/**`、`/upload/**`、`/voucher/**`，**没有放行 `/voucher-order/**`**——所以手动调这个回调还得带登录 token。真实支付平台不会带用户 token，这是应该修的边界（把 `/voucher-order/pay/callback/**` 从登录拦截里排除，改走验签）。

真实项目里的调用方式：

```text
用户付款成功
→ 微信/支付宝服务器异步 POST 到你配置的 notify_url（带签名、商户订单号、平台交易号、金额）
→ 你的回调接口：验签 → 核对订单号与金额 → 把订单改成已支付（即 payCallback 的逻辑）
→ 返回约定应答（如 SUCCESS），平台才认为通知完成
→ 没有正确应答，平台按策略重试（微信约 15s/15s/30s… 多次）
```

前端的“支付完成跳回订单页”只是页面行为，不算回调；回调必须是支付平台服务器发来的。

本项目缺的部分（面试主动说）：接支付网关、回调验签、金额校验、回调幂等记录、失败重试应答、已支付但订单被关闭时的退款。

**4.4 超时关单：先赢状态，再补库存和释放**（`VoucherOrderServiceImpl.java:193-229`）

```java
@Override
@Transactional(rollbackFor = Exception.class)
public boolean closeTimeoutOrder(Long orderId) {
    VoucherOrder order = getById(orderId);           // 等价 SQL：SELECT * FROM tb_voucher_order WHERE id = ?（后面要用 voucherId / userId）
    if (order == null) {
        return false;                                // 订单不存在，没什么可关
    }
    boolean closed = update(new LambdaUpdateWrapper<VoucherOrder>()
            .eq(VoucherOrder::getId, orderId)        // WHERE id = ?
            .eq(VoucherOrder::getStatus, 1)          // AND status = 1：只有待支付能关
            .set(VoucherOrder::getStatus, 4));       // SET status = 4（已取消）
    // 等价 SQL：UPDATE tb_voucher_order SET status = 4 WHERE id = ? AND status = 1
    if (!closed) {
        return false;                            // 影响 0 行：支付已抢先，直接结束，不碰库存
    }

    boolean restored = seckillVoucherService.update(
            new LambdaUpdateWrapper<SeckillVoucher>()
                    .eq(SeckillVoucher::getVoucherId, order.getVoucherId())   // WHERE voucher_id = ?
                    .setSql("stock = stock + 1")     // SET stock = stock + 1（MySQL 回补）
    );
    // 等价 SQL：UPDATE tb_seckill_voucher SET stock = stock + 1 WHERE voucher_id = ?
    if (!restored) {
        throw new IllegalStateException("订单已关闭但库存释放失败");   // 回滚整个事务
    }

    // 数据库关单成功后同步释放 Redis 预扣资格；脚本保证重复关单不会重复回补库存。
    // KEYS = 库存 / 名单 / 流水，ARGV = 用户 ID
    Long released = stringRedisTemplate.execute(
            ROLLBACK_SCRIPT,
            Arrays.asList(
                    RedisConstants.SECKILL_STOCK_KEY + order.getVoucherId(),
                    RedisConstants.SECKILL_ORDER_KEY + order.getVoucherId(),
                    reservationKey(order.getVoucherId(), order.getUserId())
            ),
            order.getUserId().toString()
    );
    if (released == null) {
        throw new IllegalStateException("订单已关闭但 Redis 预扣释放失败");
    }
    return true;                                     // true = 本次真的关单 + 释放
}
```

#### 5. 为什么延迟消息用 RocketMQ（选型对比）

| 方案 | 决定性因素 | 判定 |
| --- | --- | --- |
| `@Scheduled` 扫表 | 周期扫全表，时效受 Cron 限制，多实例要防重 | 淘汰 |
| Redis ZSet 延迟队列 | 轮询、抢占、重试、恢复全部自研 | 淘汰 |
| 时间轮 | 多实例协调、重启恢复复杂 | 淘汰 |
| **RocketMQ 延迟消息** | 与订单消息共用 Broker，重试/死信现成 | 采用（4.x 是固定延迟等级，精度有限） |

先列清楚「下单后 1 分钟自动关单」这个需求的硬指标，再对比：

| 需求 | 说明 |
| --- | --- |
| 延迟触发要可靠 | 关单不能丢：超时未支付的订单必须被关掉并回补库存 |
| 复用已有基建 | 订单落库已经在用 RocketMQ，再引一套延迟调度等于多一份运维 |
| 失败可重试、可观测 | 关单失败要能自动重试，还要能查到「谁失败了、为什么」 |
| 多实例不能重复触发 | 服务多副本，同一笔订单只能被关一次（或保证幂等） |
| 精度要求不高 | 只需要「1 分钟左右」，不需要秒级精确 |

**结论：不是定时任务 / 时间轮「不行」，是场景不匹配：**

- **`@Scheduled` 扫表**：实现最简单，但时效受 Cron 周期限制（1 分钟跑一次就是 0~60 秒的误差，比「1 分钟关单」这个目标本身还粗）；每次都要扫出「已超时且未支付」的订单，表越大扫得越贵；多实例还要自己加锁防重；而且它的成本随订单量**长期线性增长**；
- **Redis ZSet 延迟队列**：把到期时间当 `score`，轮询 `ZRANGEBYSCORE` 取到期任务，思路可行——但**轮询、抢占、失败重试、宕机恢复、多实例防重全要自研**，等于自己写一个简化版消息队列，而这些恰好是 RocketMQ 已经做好的；
- **时间轮**（Netty `HashedWheelTimer` 之类）：单机内存态，重启即丢；多实例要自己协调、持久化和恢复都要补。它适合「进程内短延迟任务」，不适合「订单必须被关掉」这种可靠性要求；
- **RocketMQ 延迟消息**：`syncSend(topic, msg, timeout, delayLevel)` 一行发出去，Broker 负责到期投递；重试、死信、消费位点、控制台全部现成；和订单消息共用同一个 Broker，**没有新增任何运维成本**。五条硬指标全中。

**为什么淘汰「@Scheduled 扫表」（重点展开）**

这是最容易想到、也最容易被面试官拿来对比的方案，要讲透：

1. **时效和成本互相拉扯**：想让关单准，就得把 Cron 调密（比如 5 秒一次）；调密之后每轮都要扫表，**空转成本随频率线性上升**。RocketMQ 是「到点才投递」，没有到期消息时成本为零；
2. **扫描本身随订单量变贵**：典型写法是 `WHERE status = 1 AND create_time < ?`，即使有索引，随着历史订单累积，扫描代价和锁竞争都在涨；而且它每轮只能处理「扫描那一刻已到期」的订单；
3. **多实例要自己防重**：两个副本同时扫到同一笔订单 → 两个线程都去关单。要么加分布式锁（又回到锁竞争），要么完全依赖关单自身的幂等（状态条件更新）——而后者等于承认「定时任务只是触发器，正确性还得靠业务幂等」，那不如直接用消息；
4. **重试和观测要自建**：关单失败（DB 抖动、库存服务异常）没有任何自动重试；谁失败了、失败几次、为什么失败全靠自己写日志和告警。RocketMQ 这些是消费位点 + 重试 + 死信队列的默认能力。

两边能力对照：

| 维度 | **RocketMQ 延迟消息（选）** | `@Scheduled` 扫表 | Redis ZSet 延迟队列 |
| --- | --- | --- | --- |
| 触发时效 | Broker 到点投递（固定等级，本项目 level 5 = 1 分钟） | 受 Cron 周期限制（1 分钟周期 = 0~60 秒误差） | 取决于轮询间隔 |
| 没有到期任务时的成本 | 0 | 每轮都要扫表（空转） | 每轮都要查 ZSet（空转） |
| 失败重试 | ✅ 消费重试 + 死信队列 | ❌ 自己写 | ❌ 自己写 |
| 可观测性 | ✅ 控制台可查消息、位点、重试次数 | ❌ 只有日志 | ❌ 只有日志 |
| 多实例重复触发 | 消费组内单次投递（+ 业务幂等兜底） | 要自己加锁防重 | 要自己抢占防重 |
| 宕机恢复 | Broker 持久化，重启不丢 | 靠 DB 状态，天然不丢 | 靠 ZSet 持久化 + 自己补 |
| 额外基建 | 复用已有 Broker | 无 | Redis（已有）+ 自研调度逻辑 |

一句话：**我们要的是「到点可靠地投递一次」，不是「每隔一段时间去翻一遍表」**——前者是消息队列的原生能力，后者是把调度、去重、重试、观测四件事全部自己实现一遍。这就是选 RocketMQ 延迟消息的根本原因。

**追问：延迟等级为什么是 5？精度够吗？** RocketMQ 4.x 只有 18 个固定等级（1s、5s、10s、30s、1m、2m…2h），level 5 = 1 分钟。代码里它是配置项 `seckill.rocketmq.timeout-delay-level:5`（`SeckillOrderListener.java:38-39`），在落库成功后作为 `syncSend` 的第四个参数传出（`:45-50`）。订单超时关单本来就是粗粒度场景，等级完全够用；要任意精度得升级 RocketMQ 5.x 或自建延迟服务。

**追问：延迟消息重复投递会不会关两次单？** 不会重复释放库存：关单走状态条件更新（`WHERE status = 1`），第二次影响 0 行；释放库存的 Lua 按「资格还在才释放」做幂等（返回 0 表示无事可做）。关单路径的正确性**不依赖「消息只投一次」**。

**追问：那支付回调撞上关单呢？** 两者都在改 `status`，靠同一条状态条件更新竞争，谁先改成功谁生效（本小节第 3 节有完整推演）。

#### 6. 边界（面试追问）

| 追问                 | 回答                                                         |
| ------------------ | ---------------------------------------------------------- |
| 这算乐观锁吗？            | 是 CAS 式乐观并发控制思想；没有 version 字段，准确说法是「状态条件更新」                |
| 为什么要看 update 影响行数？ | 0 行说明状态已被其他线程改走，继续回补库存会重复释放                                |
| 延迟消息重复投递呢？         | 状态更新和释放脚本都幂等，第二次影响 0 行 / 返回 0                              |
| 支付成功但订单已被关闭？       | 当前不会把已取消订单改成已支付（库存已释放），生产要接退款流程                            |
| 延迟等级为什么是 5？        | RocketMQ 4.9.4 固定等级，level 5 = 1 分钟；任意精确时间需要升级 5.x 或专门的延迟服务 |

---


## 三、故障补偿与最终一致性

> 简历第 3 条对应两问：**3.1 定时对账**（XXL-JOB 扫 Redis 预扣元数据与 MySQL 订单的差异，三个分支：未落库重投 / 已取消释放 / 已落库补名单）；**3.2 补偿账本**（把每次补偿落成 `tb_task_compensation` 一行，做有界重试与可追溯）。前者是「谁去修」，后者是「修过什么、修到第几次」。

### 3.1 XXL-JOB 对账：原单号重投、释放预扣与失败重试

*对应简历第 3 条 · 定时对账部分（原"第五问"）*

> **简历原文**：基于 XXL-JOB 定时扫描 Redis 预扣元数据与 MySQL 订单状态，对未落库订单使用原订单 ID 重投 RocketMQ，对已取消订单幂等释放 Redis 预扣，并对异常任务进行失败重试。

#### 面试话术（四段式：背景 → 剖析 → 构思 → 复盘，可直接背）

**① 背景阐述**：前面1.1、2.1、2.2解决的是"正常流程"：Lua 预扣、消息异步落库、超时关单。但线上一定会出"异常"，比如：

- Redis 预扣成功了，消息还没发出去，程序就重启了——Redis 扣了，MQ 里却没消息；
- 消息其实发到了 MQ，但程序等确认超时、误以为失败，把预扣回滚了（假失败："没收到确认"不等于"没发到"）；
- 消费者挂了或一直报错，消息堆在 MQ 里没人处理；
- 人工误操作改了 Redis / 数据库的数据。

这些异常发生时，请求线程早就结束了，实时链路上没人能立刻处理。

**② 问题剖析**：为什么"实时补偿"救不了？先看它生效的前提——出事时"当事人"必须在场：

- 发送失败能 catch 补偿，前提是请求线程还活着；
- 消费失败能靠 MQ 重试，前提是消息已经进了 Broker、消费者能恢复；
- 数据被改能修复，前提是有人发现了不对。

而这四种异常恰恰都打破了前提：进程没了线程就没了；消息没进 Broker 就没有可重试的东西；消费者"不报错、只是没动静"，也不会触发任何告警。**所以问题的本质是两句话：出事了，既没有"在场的活人"能立即处理，也没有"事后的账本"知道这件事发生过。**

由此得出这个优化点的两个关键设计，后面所有实现都是为它们服务：

1. **预扣时的凭据**：在扣库存的那一刻就把这笔操作记下来，让每一次悬空的预扣都有据可查；
2. **独立的扫描者**：一个不依赖任何请求线程、定时运行的任务，拿着凭据去核对最终结果，对不上就修。

**③ 方案构思**：整个方案就是围绕"凭据 + 扫描者"来搭：

- **凭据**：改 Lua 预扣脚本，成功时顺手写一条预扣流水（reservation）——记录订单 ID、用户、券、预扣时间，7 天过期。它是后面所有补偿的唯一凭证；流水被删除，就意味着"这件事有定论了"；
- **扫描者**：选 XXL-JOB 做调度。选型上对比过四种：@Scheduled 单机简单，但多实例会重复执行、没有日志和重试；@Scheduled + Redisson 锁只解决"谁执行"，告警和失败重试还要自建；Quartz 是老牌调度框架，但调度表要建在业务库、每次触发抢 `QRTZ_LOCKS` 多表行锁，而且没有管理台、执行日志、失败重试——它是"调度库"不是"调度平台"；XXL-JOB 调度执行分离，自带页面、日志、失败重试和路由，运维成本最低，所以选它；
- **任务逻辑**（每分钟跑一次）：SCAN 扫描全部预扣流水（用 SCAN 不用 KEYS），拿每条流水的订单 ID 去 MySQL 对账，分三个修复分支——查不到订单：说明还没落库，用**原订单 ID** 重投 MQ（复用原 ID 才能命中消费端幂等；并且加了 30 秒等待窗口 + 60 秒重投间隔两道限频，避免每分钟重复发）；订单已取消：执行和关单共用的回滚 Lua，幂等释放；订单正常：补回一人一单名单 + 删流水结案。最后再做一次数量对账（Redis 名单人数 vs 未取消订单数），不一致只告警；
- **闭环**：任何补偿失败都汇总后抛异常，让 XXL-JOB 记失败并按配置重试——执行器不自己 sleep 重试，也不吞异常。

**④ 复盘总结**：这个优化点记住两句核心——**"预扣时留凭据，事后有独立的扫描者"**；技术上说到底是"消息 + 幂等 + 定时对账"三件套，用最终一致性替代分布式事务。三个原则：重投必须复用原订单 ID、所有补偿动作必须幂等、失败必须让调度中心看得见能重试。

**先记住最小清单**：跑通 XXL-JOB 只需要下面三组东西，细节在 2.1-2.5 逐节展开。

```text
本地代码（4 项）：
  1. Maven 依赖 xxl-job-core：提供 @XxlJob（com.xxl.job.core.handler.annotation.XxlJob）
     + XxlJobSpringExecutor（com.xxl.job.core.executor.impl.XxlJobSpringExecutor）
  2. yaml（application.yaml:41-52）：Admin 地址 + accessToken + appname + 执行器端口 + logpath
  3. com.hmdp.config.XxlJobConfig：@Bean(initMethod = "start") 的执行器 Bean
  4. @XxlJob("seckillReconcileJob") 方法（com.hmdp.listener.SeckillReconciliationTask#reconcile）：
     字符串就是与 Admin 的唯一约定

Admin 网页（2 项）：
  1. 执行器管理：建 AppName = neargo-executor 的分组（注册方式选「自动注册」，机器自动上线）
  2. 任务管理：JobHandler = seckillReconcileJob（= 注解字符串）、Cron、路由策略、
     阻塞策略、失败重试次数、超时时间

触发链（3 段）：
  1. Admin：调度线程每 5 秒扫 xxl_job_info + 抢 xxl_job_lock → 写 trigger 日志 → 选机器 POST /run
  2. 执行器：EmbedServer 收 /run → 按名字查 jobHandlerRepository → JobThread 执行 @XxlJob 方法
     （先回 accepted，受理 ≠ 执行完成）
  3. 执行器：执行完 pushTriggerCallback 回 POST /api/callback（handleCode=200/500 + 日志）
     → Admin 更新 xxl_job_log → 失败由失败监控按配置重试
```

**三个最容易踩的坑**：

1. **accessToken 两侧必须一致**（`application.yaml` vs Admin 的 `application.properties`），否则注册被拒；
2. **JobHandler 名与 `@XxlJob("...")` 字符串必须一字不差**，否则执行器报 `jobHandler not found`；
3. **`/run` 先回 `accepted`，真正结果走 callback**——"HTTP 返回成功"不等于"任务执行成功"。

**全局流程图（颜色 = 谁写的代码 / 谁在运行）**：①搭建 → ②调度 → ③执行器内部（绿色块是 `reconcile()` 的完整展开）→ 回调与重试；每一段在 2.1-2.5 逐节展开。

```mermaid
flowchart TD
    subgraph SETUP["① 搭建阶段（一次性）"]
        S1["【Admin】MySQL：建库 xxl_job<br/>执行官方 tables_xxl_job.sql"] --> S2["【Admin】部署调度中心 xxl-job-admin<br/>application.properties：:8080 + 数据源 + accessToken"]
        S2 --> S3["【项目代码】业务应用引依赖 xxl-job-core<br/>+ com.hmdp.config.XxlJobConfig（地址 / token / appname / :9999）"]
        S3 --> S4["【框架 core】启动时 XxlJobSpringExecutor 扫描所有 Bean<br/>把 @XxlJob('seckillReconcileJob') 存进 jobHandlerRepository Map<br/>同时启动内嵌 HTTP 服务 :9999"]
        S4 --> S5["【框架 core】每 30 秒 POST /api/registry 注册心跳<br/>Admin 90 秒无心跳即从在线列表移除"]
        S5 --> S6["【Admin】页面任务管理新增任务<br/>JobHandler = seckillReconcileJob（= @XxlJob 字符串）<br/>Cron 0 * * * * ? / 路由策略 / 失败重试次数"]
    end

    subgraph RUN["② 运行阶段（每次 Cron 到点）"]
        R1["【Admin】调度线程每 5 秒扫 xxl_job_info<br/>trigger_next_time 到期？抢 xxl_job_lock 防集群重复"] --> R2["【Admin】先写调度日志 xxl_job_log（trigger 阶段）"]
        R2 --> R3["【Admin】按页面配置的路由策略选一台在线执行器"]
        R3 --> R4["【Admin】HTTP POST /run<br/>TriggerParam：executorHandler=seckillReconcileJob、logId"]
    end

    subgraph EXEC["③ 执行器 :9999 内部（从 /run 到 callback）"]
        E1["【框架 core】EmbedServer 收到 /run → ExecutorBizImpl.run()"] --> E2{"【框架 core】按 executorHandler 名查<br/>jobHandlerRepository Map"}
        E2 -->|"查不到"| EX1["【框架 core】返回 fail：jobHandler not found"]
        E2 -->|"命中"| E3["【框架 core】MethodJobHandler → 丢进 JobThread 的 triggerQueue<br/>立即返回 accepted（受理 ≠ 执行完成）"]
        E3 --> E4["【框架 core】JobThread 异步取出，按阻塞策略执行<br/>SERIAL 串行（默认）/ DISCARD_LATER / COVER_EARLY"]

        subgraph REC["【项目代码】com.hmdp.listener.SeckillReconciliationTask#reconcile()（:71-93）"]
            REC1["① 读开关 enabled（seckill.reconcile.enabled，yaml:38）<br/>false → 直接 return（callback 仍是 200）"] --> REC2["② hasFailure = #repairReservations()（:96-171）"]
            REC2 --> REC3["SCAN seckill:reservation:*（游标 count=100）<br/>逐条 HGETALL → #parseLong 校验 4 个字段<br/>缺字段 → log.error + hasFailure=true"]
            REC3 --> REC4{"VoucherOrder order = voucherOrderMapper.selectById(orderId)<br/>拿流水里的 orderId 对照 MySQL，结果？"}
            REC4 -->|"order == null<br/>结果未知：等"| REC5["分支一（等结果）：#shouldRetry（30s 窗口 / 60s 间隔）<br/>→ 原 orderId 重投（3000ms）<br/>· 库存/名单不动 · 流水【保留】"]
            REC4 -->|"status == 4<br/>撤销"| REC6["分支二（撤销=退单）：执行 seckill_rollback.lua 幂等释放<br/>SISMEMBER 命中 → SREM 移除资格 + stock+1 还库存 + DEL 流水"]
            REC4 -->|"status ≠ 4<br/>成交"| REC7["分支三（成交=落定）：不还库存！<br/>SADD 保留/补回名单（不能重抢） + DEL 流水结案"]
            REC5 --> REC8["③ #reconcileActiveOrderCounts()（:174-198）<br/>SCARD seckill:order:* vs COUNT(status != 4)<br/>不一致只 log.warn（不自动改）"]
            REC6 --> REC8
            REC7 --> REC8
            REC8 --> REC9{"④ hasFailure 或中途抛异常？"}
            REC9 -->|"是"| REC10["抛 IllegalStateException<br/>（消息：秒杀对账存在未完成的自动补偿）"]
            REC9 -->|"否"| REC11["正常返回（void）"]
        end

        REC10 --> E6
        REC11 --> E6
        E6["【框架 core】pushTriggerCallback：POST /api/callback<br/>handleCode=200/500 + handleMsg + 执行日志"]
    end

    S6 -.->|"任务保存即纳入调度"| R1
    R4 --> E1
    E6 --> R5["【Admin】更新 xxl_job_log（handle 阶段）"]
    R5 --> R6{"【Admin】handleCode=500 且还有重试次数？"}
    R6 -->|"是：失败监控扫到 500 后重发 /run"| R3
    R6 -->|"否"| R7["【Admin】更新 trigger_next_time，等下一次 Cron"]

    subgraph LEGEND["图例"]
        L1["【项目代码】NearGo 仓库里我们写 / 维护的"]
        L2["【Admin】调度中心：官方服务 + 页面配置 + xxl_job 库"]
        L3["【框架 core】执行器里 xxl-job-core 自动逻辑，我们不写"]
    end

    classDef project fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20
    classDef admin fill:#FFF3E0,stroke:#E65100,color:#BF360C
    classDef framework fill:#E3F2FD,stroke:#1565C0,color:#0D47A1
    class S3,REC1,REC2,REC3,REC4,REC5,REC6,REC7,REC8,REC9,REC10,REC11 project
    class S1,S2,S6,R1,R2,R3,R4,R5,R6,R7 admin
    class S4,S5,E1,E2,EX1,E3,E4,E6 framework
    class L1 project
    class L2 admin
    class L3 framework
    style REC fill:#E8F5E9,stroke:#2E7D32
```

> 读法：绿色【项目代码】= NearGo 仓库里我们维护的；橙色【Admin】= 调度中心（官方服务 + 页面配置 + `xxl_job` 库）；蓝色【框架 core】= 执行器里 xxl-job-core 的自动逻辑。

#### 1. 这条在说什么

实时链路（发送失败补偿 + 消费端幂等）覆盖不了这些情况：

- Redis 预扣成功后、消息发出前，应用进程挂了；
- Broker 已收到消息，但客户端超时认为失败（假失败）；
- 消息长期积压、消费者持续异常；
- 人工误操作导致数据不一致。

这些问题的共同点是：请求线程早就结束了，没人能立即处理。所以要有一条独立于实时链路的**对账任务**，靠 Redis 里保存的预扣流水（reservation）去发现「预扣了但订单没落库」的情况，并修复。

四种情况拆开看：

| 场景                      | 实时链路为什么覆盖不了                | 对账能做什么                                |
| ----------------------- | -------------------------- | ------------------------------------- |
| ① 预扣成功后进程挂了             | catch 是给活着的线程用的，进程没了没人执行补偿 | 完全修复：扫到流水、无订单、超过等待窗口 → 用原订单 ID 重投     |
| ② Broker 已收到但客户端超时（假失败） | 补偿执行了但方向错（消息其实在途），无法撤回     | 数量对账能发现并告警；自动修复有限（需消息轨迹等更强手段）         |
| ③ 消息积压 / 消费者持续异常        | 消费没发生，幂等和补偿都没有入口           | 重投保证预扣意图不丢；消费者恢复后落库；期间数量对账告警          |
| ④ 人工误操作                 | 人工操作不触发任何代码路径              | 视情况：已取消订单 → 释放；已落库缺名单 → 补回；纯数量差异 → 告警 |

**这条优化的意义**

- **系统自愈**：进程崩溃、消息丢失 / 积压、假失败、人工误操作这些「请求线程早就结束了」的缺口，由独立对账任务发现并修复；
- **让异步敢于存在**：正因为有每分钟一轮的对账兜底，「Redis 预扣 + MQ 异步落库」才敢允许失败、允许短暂不一致；
- **没有它会怎样**：预扣了但订单没落库的请求永远悬空——Redis 库存白扣、用户拿不到订单；已取消订单继续占用 Redis 资格；Redis 与 MySQL 的数量差没人发现，越积越多；
- **本质**：不做分布式事务，用「消息 + 幂等 + 定时对账」把不一致逐步收敛，属于最终一致性。

**② 里的“客户端超时”是多久？**

三处 `syncSend` 都显式传了 **3000 毫秒（3 秒）**：

```java
// 1) com.hmdp.service.impl.VoucherOrderServiceImpl#seckillVoucher（VoucherOrderServiceImpl.java:105）
rocketMQTemplate.syncSend(orderTopic, JSONUtil.toJsonStr(order), 3000);        // 下单消息
// 2) com.hmdp.listener.SeckillOrderListener#onMessage（SeckillOrderListener.java:45）
rocketMQTemplate.syncSend(timeoutTopic, message, 3000, timeoutDelayLevel);     // 延迟关单消息
// 3) com.hmdp.listener.SeckillReconciliationTask#repairReservations（SeckillReconciliationTask.java:130）
rocketMQTemplate.syncSend(orderTopic, JSONUtil.toJsonStr(retryOrder), 3000);   // 对账重投
// rocketMQTemplate = org.apache.rocketmq.spring.core.RocketMQTemplate；JSONUtil = cn.hutool.json.JSONUtil
```

`application.yaml` 里的 `rocketmq.producer.send-message-timeout: 3000` 是给不传 timeout 的重载用的默认值。

这个超时限制的是「生产者等 Broker 返回发送结果的最长时间」：3 秒内等不到结果就抛异常 → 进 catch 执行补偿。要注意两点：

- RocketMQ 同步发送默认 `retryTimesWhenSendFailed = 2`，一次调用内部可能实际发了多条，“抛异常”和“Broker 到底收到几条”并非一一对应；
- 调大超时能降低假失败概率，但会拖长接口线程阻塞时间；调小则误判更多。所以不跟超时较劲，最终仍靠消费端幂等 + 对账收敛。

对账任务本身由 XXL-JOB 调度：**「谁触发、怎么触发、失败怎么重试」在第 2 节完整展开；「触发之后扫描逻辑怎么修」在第 4 节。**

**第 3 章完整流程图：**

![NearGo XXL-JOB 定时触发、预扣修复与数量对账流程](./images/neargo-xxl-job-reconciliation.png)

图中 `status = 4` 表示订单**已取消**。`repairReservations()` 处理预扣记录后，`reconcile()` 接着执行 `reconcileActiveOrderCounts()`；单纯数量不一致目前只输出 `log.warn`，处理异常或扫描异常才会让本轮任务失败。

#### 2. XXL-JOB 完整链路：调度中心（Admin）与执行器（NearGo）

> 第 4 节的 mermaid 只画了「任务被触发之后、对账代码内部」的步骤。这里把整条链路补全：
> **谁注册、谁配任务、谁触发、谁执行、结果怎么回去、失败怎么重试**。
> 一句话类比：闹钟和铃声（Cron）都在 Admin，NearGo 只准备一段「被叫醒后能执行」的代码。

##### 2.1 两个角色，各管什么

| 角色           | 是谁            | 部署形态                                  | 职责                                                    |
| ------------ | ------------- | ------------------------------------- | ----------------------------------------------------- |
| 调度中心 Admin   | xxl-job-admin | 独立 Web 服务，默认 8080，路径 `/xxl-job-admin` | 管执行器在线列表、管任务配置（Cron / 路由 / 重试）、到点发起调度、收集执行结果与日志       |
| 执行器 Executor | NearGo 应用     | 本项目，内嵌 HTTP 服务，默认 9999                | 启动时向 Admin 注册；收到调度请求后找到 `@XxlJob` 方法执行；把结果和日志回报 Admin |

关键理解：**Cron 不在 NearGo 代码里，而在 Admin 自己的 MySQL 库里**（哪个库、哪些表、Admin 怎么连库，见 2.3 节）。NearGo 只提供一段「可被调用的代码」（JobHandler）；什么时候调、调失败怎么办，都由 Admin 说了算。

两边的约定集中在 `application.yaml:41-52`：

```yaml
xxl:
  job:
    admin:
      addresses: http://127.0.0.1:8080/xxl-job-admin   # 执行器往哪个 Admin 注册、回报
      accessToken: default_token                       # 通信令牌，两边必须一致，否则注册被拒
    executor:
      appname: neargo-executor      # 执行器身份名：Admin 建任务时按这个名字选机器
      ip: 127.0.0.1                 # 注册给 Admin 的 IP
      port: 9999                    # 内嵌 HTTP 端口：Admin 调度时调 http://ip:port/run
```

##### 2.2 启动阶段：执行器代码做了什么

`XxlJobConfig.java:41-53` 的 Bean 带 `initMethod = "start"`，Spring 启动过程中 `XxlJobSpringExecutor` 完成四件事：

```text
XxlJobSpringExecutor 随 Spring 启动（Bean 定义见 XxlJobConfig.java:41）
  ├─ ① initAdminBizList
  │     解析 xxl.job.admin.addresses，建立到 Admin 的 HTTP 客户端（注册、回调都走它）
  ├─ ② 扫描 @XxlJob 并注册本地 Map
  │     { "seckillReconcileJob" → MethodJobHandler（包装 com.hmdp.listener.SeckillReconciliationTask#reconcile） }
  │     （SeckillReconciliationTask.java:71 的注解就是在这里被发现的）
  ├─ ③ initEmbedServer
  │     启动内嵌 HTTP 服务，监听 9999，暴露 /run 接口（Admin 调度请求的入口）
  └─ ④ initRegistryThread
        后台线程每 30 秒 POST {Admin}/api/registry：
        appName = neargo-executor，address = http://127.0.0.1:9999
        Admin 把它写进注册表，页面「执行器管理」里就能看到这台在线机器
```

两个要点：

- 「执行器注册」不需要手填 IP，应用一启动就自动报到；应用关闭时还会调一次 `/api/registryRemove` 主动下线；
- Admin 侧有注册监控线程：超过 90 秒收不到某台机器的注册心跳，就把它从在线列表移除（防僵尸节点）。

##### 2.3 配置阶段：Admin 自己的 MySQL 库，以及页面上的任务配置

**Cron 存在哪个库？——Admin 自己的 MySQL 库，不是 NearGo 的业务库。**

XXL-JOB Admin 是独立服务，要单独给它建一个 MySQL 库（官方建表脚本 `tables_xxl_job.sql`，常用库名 `xxl_job`；2.4.1 官方只提供 MySQL 脚本），它和 NearGo 的业务库（本项目是 `hmdp`）是两个互不相干的库：

| Admin 侧的表          | 存什么                                                                                     |
| ------------------ | --------------------------------------------------------------------------------------- |
| `xxl_job_info`     | 任务配置：JobHandler、**Cron**（`schedule_type=CRON`、`schedule_conf=0 * * * * ?`）、路由策略、失败重试次数等 |
| `xxl_job_group`    | 执行器列表：`AppName=neargo-executor`                                                         |
| `xxl_job_registry` | 执行器注册心跳（就是 2.2 节那个每 30 秒的 `POST /api/registry` 写进来的地方）                                  |
| `xxl_job_log`      | 每次调度的记录与结果（成功 / 失败 / 重试）                                                                |
| `xxl_job_user`     | Admin 登录账号（默认 `admin / 123456`）                                                         |
| `xxl_job_lock`     | 调度锁：Admin 集群多实例时，保证同一时刻只有一个实例在触发任务                                                      |

Admin 连哪个库、用什么账号，写在 **Admin 自己的配置文件**（`xxl-job-admin` 的 `application.properties`），不是 NearGo 的 `application.yaml`：

```properties
server.port=8080
server.servlet.context-path=/xxl-job-admin
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
spring.datasource.username=root
spring.datasource.password=你的密码
# 必须与 NearGo 的 xxl.job.admin.accessToken 一致（properties 文件不支持行内注释）
xxl.job.accessToken=default_token
```

从零配置的顺序（本项目用 XXL-JOB 2.4.1）：

1. MySQL 建库 `xxl_job`，执行官方 `tables_xxl_job.sql` 初始化上面这些表；
2. 启动 Admin：官方 jar（`java -jar xxl-job-admin-2.4.1.jar`）或官方 docker 镜像 `xuxueli/xxl-job-admin:2.4.1`，数据源参数用上一段配置（docker 部署时要把里面的 `127.0.0.1` 换成真实的 MySQL 地址）；
3. 浏览器打开 `http://127.0.0.1:8080/xxl-job-admin`，默认账号 `admin / 123456`；
4. 「执行器管理 → 新增」：AppName 填 `neargo-executor`，注册方式选**自动注册**（NearGo 启动后自动上线，机制见 2.2）；
5. 「任务管理 → 新增」：按下表把任务建出来，保存后立即纳入 Cron 调度。

页面「任务管理 → 新增任务」里与本项目有关的配置：

| 配置项        | 填什么                   | 作用                                                          |
| ---------- | --------------------- | ----------------------------------------------------------- |
| 执行器        | `neargo-executor`     | 决定调度请求发给哪批机器                                                |
| 运行模式       | `BEAN`                | 表示执行代码里的 `@XxlJob` 方法（不是 GLUE 脚本）                           |
| JobHandler | `seckillReconcileJob` | 必须和 `@XxlJob("seckillReconcileJob")` 完全一致，执行器靠它在本地 Map 里查方法 |
| Cron       | 如 `0 * * * * ?`（每分钟）  | 调度频率；改频率只改 Admin，不动代码、不重启应用                                 |
| 路由策略       | 故障转移（FAILOVER）        | 多台执行器时选哪台跑；执行器无响应时自动换一台，防单点                                 |
| 阻塞处理策略     | 串行策略（默认）              | 上一轮还没跑完、下一轮又到点时怎么办                                          |
| 失败重试次数     | 1                     | 执行失败后 Admin 自动再触发 1 次                                       |
| 任务超时时间     | 如 60s                 | 超时由 Admin 主动中断并记失败                                          |

`JobHandler` 这一格和 `@XxlJob` 的字符串是**跨进程的唯一约定**：Admin 只传一个名字，执行器用这个名字在本地 Map 里查方法。两边名字对不上，执行器会报 `jobHandler not found`。

职责划分一句话：**NearGo 侧只配两样（Admin 地址 + accessToken）；Cron、路由、失败重试、在线机器列表，全部在 Admin 的 MySQL 库和页面上。**

仓库里这份配置同样记录在 `docs/architecture/xxl-job-reconciliation.md`，包含 AppName / JobHandler / Cron / 路由策略 / 失败重试的完整取值。

##### 2.4 运行阶段：Admin 与执行器的完整交互（全链路时序图）

```mermaid
sequenceDiagram
    autonumber
    participant Admin as XXL-JOB Admin 调度中心<br/>独立服务 :8080
    participant Exec as NearGo 执行器<br/>内嵌 HTTP :9999
    participant Redis as Redis
    participant MySQL as MySQL

    rect rgb(235, 235, 255)
    Note over Exec,Admin: 一、启动注册
    Exec->>Exec: XxlJobSpringExecutor 启动<br/>扫描 @XxlJob 存入 jobHandlerRepository<br/>内嵌 HTTP 服务监听 9999
    Exec->>Admin: POST /api/registry<br/>appName=neargo-executor, address=127.0.0.1:9999
    loop 每 30 秒心跳
        Exec->>Admin: POST /api/registry（保活）
    end
    end

    rect rgb(235, 255, 235)
    Note over Admin: 二、任务配置（页面操作，不写代码）
    Note over Admin: 执行器=neargo-executor，运行模式=BEAN<br/>JobHandler=seckillReconcileJob，Cron=0 * * * * ?<br/>路由=故障转移，阻塞策略=串行，失败重试=1
    end

    rect rgb(255, 245, 235)
    Note over Admin,Exec: 三、到点调度
    Admin->>Admin: Cron 到点 → 调度线程按路由策略选一台在线机器
    Admin->>Exec: HTTP POST /run<br/>TriggerParam：jobId、executorHandler=seckillReconcileJob、logId
    Exec->>Exec: 按 executorHandler 查本地 Map<br/>→ MethodJobHandler → 反射调用 reconcile()
    Exec-->>Admin: 受理响应（trigger accepted）
    Note over Admin,Exec: 受理 ≠ 执行完成，真正结果走第五段 callback
    end

    rect rgb(255, 235, 235)
    Note over Exec,MySQL: 四、执行对账（reconcile 内部，详见第 4 节）
    Exec->>Redis: SCAN seckill:reservation:*
    Exec->>MySQL: SELECT * FROM tb_voucher_order WHERE id = ?
    alt 订单不存在且超过等待窗口
        Exec->>Exec: 原 orderId 重投 MQ；retryCount+1、lastRetryAt 更新
    else status = 4（已取消）
        Exec->>Redis: seckill_rollback.lua 幂等释放预扣
    else 已落库且未取消（status ≠ 4）
        Exec->>Redis: SADD 补回名单 + DEL 删除流水
    end
    Exec->>Redis: SCAN seckill:order:* 取 SCARD 名单人数
    Exec->>MySQL: COUNT(*) WHERE voucher_id=? AND status != 4
    end

    rect rgb(255, 250, 215)
    Note over Admin,Exec: 五、结果回报与失败重试
    Exec->>Admin: POST /api/callback<br/>logId, handleCode=200 成功 / 500 失败, handleMsg, 执行日志
    alt handleCode = 200
        Note over Admin: 调度日志记为「成功」，页面可查完整执行日志
    else handleCode = 500
        Note over Admin: 失败监控线程：重试次数 > 0 → 再次触发本任务
        Admin->>Exec: 再次 POST /run（重试）
        Note over Admin: 重试仍失败 → 记为失败并告警
    end
    end
```

这五段就是简历「基于 XXL-JOB 定时扫描」背后的完整过程：**注册 → 配置 → 调度 → 执行 → 回报**。

##### 2.5 失败重试发生在 Admin，不在执行器里

- 执行器只负责「如实上报」：`reconcile()` 抛异常 → callback 的 `handleCode = 500`，执行器自己不重试；
- Admin 的失败监控线程扫到 500，且任务配置的「失败重试次数 > 0」→ 再次发 `/run`；
- 每次重试在页面都是一条独立的调度日志，能看出哪一次成功；
- 所以 `SeckillReconciliationTask.java:85-92` 的 catch 里「打日志后必须重新抛出」是必要动作：一旦吞掉异常，callback 会报 200，Admin 认为成功，永远不重试，预扣流水就没人修了。

#### 3. 预扣流水（reservation）字段说明

Lua 预扣时写的那条流水（1.1 的 4.1 第 6 步），是这条链路的依据：

| 字段            | 含义           | 用途                   |
| ------------- | ------------ | -------------------- |
| `orderId`     | 本次预扣绑定的订单 ID | 重投时**必须复用它**，保证消费端幂等 |
| `userId`      | 用户 ID        | 修复名单、释放资格            |
| `voucherId`   | 券 ID         | 拼释放脚本的 Key           |
| `reservedAt`  | 预扣时间（秒）      | 判断是否超过等待窗口           |
| `retryCount`  | 已自动重投次数      | 控制重投次数               |
| `lastRetryAt` | 最近一次重投时间（秒）  | 控制重投频率               |

#### 4. 对账执行步骤（reconcile 内部流程）

> 第 2 节讲的是任务「怎么被 Admin 触发到这里」；本节只看执行器收到 `/run` 之后、对账代码内部的逻辑。

```mermaid
flowchart TD
    A["执行器 /run → reconcile()（见第 2 节）"] --> B["SCAN seckill:reservation:*"]
    B --> C{"MySQL 存在该 orderId?"}
    C -->|"不存在且超过等待窗口<br/>结果未知"| D["原订单 ID 重投 MQ<br/>库存/名单不动 · 流水【保留】"]
    C -->|"已取消 status=4<br/>撤销"| E["还库存(+1) + SREM 移除资格 + 删流水"]
    C -->|"已落库<br/>成交"| F["不还库存 · SADD 保留资格 + 删流水"]
    D --> G{"本轮有失败?"}
    E --> G
    F --> G
    G -->|"是"| H["抛异常，XXL-JOB 重试"]
    G -->|"否"| I["再做数量对账"]
```

1. `SCAN seckill:reservation:*`，逐条读预扣流水；

2. 用流水里的 `orderId` 查 MySQL，按订单状态分三个分支：
   
   - **订单不存在，且预扣时间超过等待窗口**（默认 30 秒）→ 用**原订单 ID** 重投 MQ（`retryCount + 1`、`lastRetryAt = 当前时间`）；
   
   - **订单已取消（status = 4）** → 执行 `seckill_rollback.lua` 幂等释放 Redis 库存和名单；
   
   - **订单正常落库（status ≠ 4，含 1/2/3）** → 补回用户名单（`SADD`）+ 删除流水；

   **三分支的本质：三种"世界状态"**（删流水是共同点，"还不还"才是区别）

   | | 分支一：订单不存在 | 分支二：status = 4 | 分支三：正常 |
   |---|---|---|---|
   | 结果 | **未知**（等） | **撤销**（退单） | **成交**（落定） |
   | 库存 `seckill:stock` | 不动 | **+1 还回** | 保持已扣（**不还**） |
   | 资格名单 `seckill:order` | 不动 | **SREM 移除**（可重新抢） | **SADD 保留/补回**（不能重抢） |
   | 流水 `seckill:reservation` | **保留**（继续盯） | 删除（销账） | 删除（销账） |
   | 若不这么做 | 流水悬空、没人修 | 不还 → 少卖、资格被锁 | 还了 → 库存虚增、**超卖** |

   > 一句话：**分支二、三都要删流水（收尾动作相同），但"还不还库存/资格"方向相反——撤销要还，成交不还。** 这才是三分支的本质区别。

3. 本轮有任何失败 → 抛异常，让 XXL-JOB 把这次执行记为失败并按配置重试；

4. 再做一次**数量对账**：Redis 名单人数 vs MySQL 未取消订单数，不一致打警告日志。

重投限制（防止每轮 Cron 都重复发消息）：

- 预扣后至少等 `stale-seconds`（默认 30 秒）才允许重投，给正常消费者留落库时间；
- 同一个订单两次重投之间至少间隔 `retry-interval-seconds`（默认 60 秒）。

**为什么重投必须复用原订单 ID**：如果每次重投生成新 ID，消费端「订单 ID 幂等」就失效了，一次预扣可能被修复成多笔订单；复用原 ID 后，无论投多少次都指向同一条订单。

**为什么订单已落库还要 SADD 补名单？**（分支三最容易被追问）

**先说人话版（三步看懂分支三）**

**第一步｜一个关键事实**：消费端落库成功后**不会回来删 Redis 的流水**——流水是抢券那一刻 Lua 写的，消费端只往 MySQL 插订单，插完就结束，它不碰 Redis。所以流水会一直躺在 Redis 里；对账每分钟扫一次，扫到就必须处理、处理完必须删，否则下一分钟又扫到、再处理一遍。

**第二步｜三分支 = 对账拿着流水的 orderId 问 MySQL「这单后来怎么了」**，只有三种答案：

| MySQL 查到的答案   | 对账做的事                           |
| ------------- | ------------------------------- |
| 查不到订单         | 没落库 → **重投 MQ**，让它去落库           |
| 订单状态 = 4（已取消） | 被撤销了 → **还库存 + 还资格（SREM）+ 删流水** |
| 订单在、状态 ≠ 4    | 成功落库 → **删流水结案**（顺手 SADD）       |

看第三行：成功也要处理——**每条流水都得有人删**，分支三就是给已完成的记录收尾归档。

**第三步｜SADD 是顺手买的保险**：正常情况用户在抢券那一刻就被 Lua 加进名单了，这里再 SADD = **什么都没做**（往集合里重复加同一成员，集合不变）。它只在名单被意外弄丢时起作用（手删 Key、Redis 丢数据、运维误清）：那时用户能再抢一次 → 库存白扣、新流水被对账反复重投；SADD 补回名单后，再抢会命中 Lua 的 `return 2`，烂摊子就不会发生。

> 下面的表格是同一件事的逐帧拆解：先用人话版建立直觉，再看细节。

先分清「谁、在什么时候、对 Redis 做什么」——流水不是消费者删的：

| 阶段      | 执行者                      | 对 Redis 的操作                                      |
| ------- | ------------------------ | ------------------------------------------------ |
| 秒杀预扣    | Lua（请求线程）                | `SADD` 名单 + `HSET` 流水（同一次原子执行）                   |
| 落库成功    | 消费者 `createVoucherOrder` | **不碰 Redis**：不删流水、不删名单                           |
| 订单取消    | 关单 / 对账分支二               | `seckill_rollback.lua`：`SREM` + 库存 +1 + `DEL` 流水 |
| 对账发现已落库 | 分支三（本分支）                 | `SADD` 名单（正常时是空操作）+ `DEL` 流水                     |

先纠正一个容易混淆的点：**删流水 ≠ 撤销预扣。**

三个 Key 各管各的，成功和撤销是两条相反的收尾路径：

| 出路                  | 场景          | 谁执行                              | 库存         | 名单                   | 流水       |
| ------------------- | ----------- | -------------------------------- | ---------- | -------------------- | -------- |
| **成功**（订单已落库）       | 对账分支三       | 对账任务                             | 保持已扣（交易有效） | `SADD` 保持 / 补回「已抢」资格 | `DEL` 结案 |
| **撤销**（发送失败 / 订单取消） | 实时补偿、关单、分支二 | `seckill_rollback.lua`（一个脚本三处共用） | `+1` 还回    | `SREM` 移除资格          | `DEL` 结案 |

也就是说：流水是对账用的「在途凭据」，结果一旦确定（成功或撤销）就该删；但只有**撤销**才需要把库存和名单一起还回去。

**再补一层：为什么订单落库后要删流水？——流水的身份是「待办条」，不是「成功记录」：**

- 它唯一的职责是回答「这笔预扣出结果了吗」：流水存在 = 结果未知（可能没落库）、对账要盯；流水删除 = 已有定论（成功结案或已撤销）、对账不用再管。订单落库 = 结案 → 销账（消费者自己不清，由对账来删，见上表）；
- 不删的后果：对账每分钟 SCAN 都会把它重新当成「待处理」再走一遍分支三，直到 7 天 TTL——扫描集里永远混着早就结案的流水，分不清谁真需要关注；
- 成功的事实没丢：MySQL 订单 + Redis 已扣库存 + Redis 名单都在，Redis 不需要再留一份「我成功了」的副本；
- 销账的三条路 + 兜底：成功 → 分支三删除；取消 → `seckill_rollback.lua` 删除；发送失败 → 同一个 `seckill_rollback.lua` 删除；都没走到 → 7 天 TTL 自动淘汰；
- 类比：流水 = 快递「在途运单」，订单落库 = 已签收。签收后撕掉运单，但包裹（扣的库存、抢到的资格）保留——撕运单 ≠ 退货。

剩下就是 `SADD` 本身：

- 同一分支的 `SADD` 是**幂等空操作**：Set 里已经有这个 userId 时，再 `SADD` 不改变任何数据（返回 0）。正常链路里用户早在 Lua 预扣那一步就被加入名单、之后也没人移走，所以这里相当于「再确认一次」；只有异常时（Redis 名单缺失、Key 被误删、人工修复）它才真正写入；
- 不补的后果：该用户再次秒杀会通过 Lua（库存 -1、写出一条新流水），但消费端的 user+voucher 校验会直接 return，订单永远不会产生 → 这条流水被对账反复重投（60 秒一次，直到 7 天 TTL），Redis 库存也白白扣掉；
- 补上之后，再抢会命中 Lua 的 `return 2`（重复下单），不扣库存、不产生新流水。

#### 5. 核心代码

**本节导航**：5 个方法 + 1 条时间线，先看谁调用谁，再看代码。

| 小节  | 方法（全限定名）                                                  | 行号       | 职责                           | 调用方                           |
| --- | --------------------------------------------------------- | -------- | ---------------------------- | ----------------------------- |
| 5.1 | `com.hmdp.listener.SeckillReconciliationTask#reconcile()` | :71-93   | XXL-JOB 入口：开关 → 编排 → 汇总成败    | XXL-JOB 反射调用                  |
| 5.2 | `#repairReservations()`                                   | :96-171  | 明细对账：三个修复分支（重投 / 释放 / 补名单）   | `#reconcile()`（:79）           |
| 5.3 | `#shouldRetry()`                                          | :200-207 | 重投限频：等待窗口 + 重投间隔             | `#repairReservations()`（:121） |
| 5.4 | `#reconcileActiveOrderCounts()`                           | :174-198 | 数量对账：SCARD 名单 vs COUNT 未取消订单 | `#reconcile()`（:80）           |
| 5.5 | `com.hmdp.config.XxlJobConfig#xxlJobExecutor()`           | :41-53   | 执行器 Bean：随 Spring 启动注册       | Spring                        |
| 5.6 | 一次完整执行的时间线                                                | —        | 把 5.1-5.4 串起来看               | —                             |

**5.1 Job 入口：失败不能吞异常**（`com.hmdp.listener.SeckillReconciliationTask#reconcile()`，`SeckillReconciliationTask.java:71-93`）

```java
71  @XxlJob("seckillReconcileJob")              // ① 注册名：与 Admin 的 executor_handler 一字不差
72  public void reconcile() {                   // ② 无参、void：返回什么都不会被 XXL-JOB 读
73      if (!enabled) {                         // ③ enabled ← seckill.reconcile.enabled（yaml:38，环境变量可覆盖）
74          return;                             // ④ 注意：正常返回 = 成功 → callback 200，"成功但什么都没干"
75      }
76
77      boolean hasFailure = false;             // ⑤ 本轮"记录级失败"的汇总标记
78      try {
79          hasFailure = repairReservations();  // ⑥ 明细对账：修流水，返回是否有修不动的
80          reconcileActiveOrderCounts();       // ⑦ 数量对账：只告警；它自己抛异常会直接跳 catch
81          if (hasFailure) {
82              // 把"记录级失败"升级成"任务级失败"
83              throw new IllegalStateException("秒杀对账存在未完成的自动补偿");
84          }
85      } catch (Exception e) {
86          log.error("秒杀对账任务执行失败", e);   // ⑧ 写执行器应用日志（不是任务日志文件，见第三节）
87          // 不能吞：吞掉 callback 会是 200，Admin 永远不重试
88          if (e instanceof RuntimeException) {
89              throw (RuntimeException) e;     // ⑨ 原样上抛：保留类型和消息（比如 83 行那句）
90          }
91          throw new IllegalStateException(e); // ⑩ 受检异常包一层，保证 XXL-JOB 收到失败
92      }
93  }
```

**逐行要点（三个最容易看漏的地方）**

1. `if (!enabled) return;`（:73-75）：对账紧急关闭时方法**正常返回 → callback 200**，页面一片绿但实际没干活，这是"停对账不报红"的刻意设计，不是 bug；
2. 79/80 行是**先跑完再算账**：即使 `repairReservations()` 已经发现失败，数量对账照样执行，一轮调度尽量收集全部问题，最后才统一决定成败；
3. 方法**无返回值（void）**：对 XXL-JOB 来说「正常返回 = 成功 / 抛异常 = 失败」，它没有走 `XxlJobHelper.handleFail()`，统一用抛异常表达失败。

**`hasFailure = true` 从哪来（`repairReservations()` 的 5 个出口）**

| 位置       | 场景                                                              |
| -------- | --------------------------------------------------------------- |
| :111-114 | 流水 Hash 缺字段（orderId / userId / voucherId / reservedAt 任一为 null） |
| :138-142 | 重投 MQ 抛异常（Broker 不可用、发送超时）                                      |
| :149-151 | 释放脚本返回 null（Redis 执行失败）                                         |
| :161-164 | 单条记录处理中任何其他异常（记失败后继续下一条）                                        |
| :166-169 | SCAN 本身失败 → `return true`（整轮直接失败）                               |

**返回 / 抛出之后，执行器内部发生了什么**（Admin 侧的触发与重试见 2.4 / 2.5）

- `MethodJobHandler.invoke`：启动时扫描 `@XxlJob` 生成的处理器，内部持有 `target`（Spring Bean `com.hmdp.listener.SeckillReconciliationTask`）和 `method`（`reconcile`），`method.invoke(target)` 反射执行；我们的方法无参、void，正常返回即成功；
- 抛异常时：JobThread 取异常消息作为 `handleMsg`（如"秒杀对账存在未完成的自动补偿"），堆栈写进**执行器本地任务日志** `{logPath}/{logId}/xxl-job.log`（`logPath` 见 `application.yaml:41-52`）；
- `pushTriggerCallback`：组装 `TriggerCallbackParam { logId, handleCode, handleMsg }`（成功 200 / 失败 500），HTTP `POST {Admin}/api/callback` → Admin 更新 `xxl_job_log`；500 由失败监控线程按任务配置重试。

**为什么 `reconcile()` 故意写得这么薄**

- **编排与执行分离**：入口只管「开关、顺序、成败」，业务在 5.2 / 5.4 两个方法里，可单独测试、单独调用；
- **失败聚合**：先修完能修的（3 条流水 2 条成功），再统一报失败，避免第一条出错就整体中断；
- **重试交给 Admin**：执行器抛异常即结束，不自己 sleep 重试，否则 JobThread 队列被占、后续调度被阻塞。

**5.2 `repairReservations()`：扫描 → 解析 → 三个修复分支**（`com.hmdp.listener.SeckillReconciliationTask#repairReservations()`，`SeckillReconciliationTask.java:96-171`；代码分两段：第一段 :96-116 扫描与解析，第二段 :117-160 三个分支）

> 先分清三个 Key 再读代码（以扫到的流水 `seckill:reservation:1001:2` 为例，其中 `voucherId=1001`、`userId=2`）：
> 
> | 代码里的变量                                       | 实际 Key                                                                     | 类型与内容                                                                     | 谁在用                                               |
> | -------------------------------------------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------- | ------------------------------------------------- |
> | `orderKey` = `SECKILL_ORDER_KEY + voucherId` | `seckill:order:1001`                                                       | Set，成员是 userId（这里是 `2`）                                                   | **分支三 `SADD` 补的就是它**；Lua 里一人一单的 `SISMEMBER` 查的也是它 |
> | `reservationKey` = SCAN 扫到的键                 | `seckill:reservation:1001:2`                                               | Hash：orderId / userId / voucherId / reservedAt / retryCount / lastRetryAt | 本次处理对象；分支三处理完 `DEL` 删掉                            |
> | 释放脚本的三个 KEYS                                 | `seckill:stock:1001` / `seckill:order:1001` / `seckill:reservation:1001:2` | String / Set / Hash                                                       | 分支二 `seckill_rollback.lua` 使用                     |
> 
> 所以分支三读作：**把 userId `2` 加回集合 `seckill:order:1001`，再删除 Hash `seckill:reservation:1001:2`**。

```java
// 文件：src/main/java/com/hmdp/listener/SeckillReconciliationTask.java
// 类：com.hmdp.listener.SeckillReconciliationTask
// 方法：private boolean repairReservations()（:96-171）
// 【第一段】扫描 + 解析（:96-116）

private boolean repairReservations() {
    boolean hasFailure = false;
    try (Cursor<String> cursor = stringRedisTemplate.scan(       // Redis SCAN：游标分批遍历，不阻塞
            ScanOptions.scanOptions()
                    .match(RedisConstants.SECKILL_RESERVATION_KEY + "*")   // 匹配 seckill:reservation:*
                    .count(100)                                            // 每批约 100 个（提示值，不保证精确）
                    .build())) {
        while (cursor.hasNext()) {                 // 本批取完自动用新游标继续 SCAN，直到游标回到 0
            String reservationKey = cursor.next(); // 一条流水，如 seckill:reservation:1001:2
            try {
                Map<Object, Object> fields = stringRedisTemplate.opsForHash()
                        .entries(reservationKey);  // HGETALL：取 orderId / userId / voucherId / reservedAt / retryCount / lastRetryAt
                Long orderId = parseLong(fields.get(ORDER_ID_FIELD));
                Long userId = parseLong(fields.get(USER_ID_FIELD));
                Long voucherId = parseLong(fields.get(VOUCHER_ID_FIELD));
                Long reservedAt = parseLong(fields.get(RESERVED_AT_FIELD));
                if (orderId == null || userId == null || voucherId == null || reservedAt == null) {
                    log.error("秒杀预扣元数据不完整 reservationKey={}, fields={}", reservationKey, fields);
                    hasFailure = true;
                    continue;                  // 字段缺失没法对账：记失败、跳过这条
                }
                // ↓↓↓ 解析出的 4 个字段交给【第二段】的三个修复分支 ↓↓↓
```

**SCAN 三条要点**：① 游标分批遍历（不是 `KEYS` 那样一次性阻塞）；② `COUNT 100` 只是“每批大概多少个”的提示值，不保证精确；③ 扫描期间 key 增删可能导致重复返回同一个 key——所以下面的三个分支全部做了幂等。

```java
// 【第二段】对账 + 三个修复分支（:117-160）

VoucherOrder order = voucherOrderMapper.selectById(orderId);            // 拿流水里的 orderId 查订单
String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;

if (order == null) {                                                    // 分支一：订单未落库 → 重投
    if (!shouldRetry(fields, reservedAt)) {
        continue;                                                       // 未超等待窗口 / 未到重投间隔 → 本轮跳过
    }
    VoucherOrder retryOrder = new VoucherOrder()
            .setId(orderId)                                             // 关键：复用原订单 ID，消费端幂等才不会失效
            .setUserId(userId)
            .setVoucherId(voucherId);
    rocketMQTemplate.syncSend(orderTopic, JSONUtil.toJsonStr(retryOrder), 3000);
    stringRedisTemplate.opsForHash().increment(reservationKey, RETRY_COUNT_FIELD, 1);   // retryCount +1
    stringRedisTemplate.opsForHash().put(reservationKey, LAST_RETRY_AT_FIELD,
            String.valueOf(Instant.now().getEpochSecond()));            // lastRetryAt = 当前秒
    continue;
}

if (Integer.valueOf(4).equals(order.getStatus())) {                     // 分支二：已取消 → 释放预扣
    releaseRedisReservation(order.getVoucherId(), order.getUserId());
} else {                                                                // 分支三：已落库 → 补名单 + 删流水
    stringRedisTemplate.opsForSet().add(orderKey, userId.toString());
    stringRedisTemplate.delete(reservationKey);
}
```

**代码里每个符号的归属**（看不懂某个调用时，按这张表定位源码）：

| 代码里的写法                                      | 归属（全限定类#方法 / 字段）                                                                            | 位置与说明                                                 |
| ------------------------------------------- | ------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| `voucherOrderMapper`                        | `com.hmdp.listener.SeckillReconciliationTask` 字段 :53 = `com.hmdp.mapper.VoucherOrderMapper` | 继承 MyBatis-Plus `BaseMapper`                          |
| `.selectById(orderId)`                      | `VoucherOrderMapper#selectById`                                                             | SQL：`SELECT * FROM tb_voucher_order WHERE id = ?`     |
| `shouldRetry(fields, reservedAt)`           | `SeckillReconciliationTask#shouldRetry`                                                     | 本类 :200-207（见 5.3）                                    |
| `rocketMQTemplate`                          | 本类字段 :55 = `org.apache.rocketmq.spring.core.RocketMQTemplate`                               | 重投用（3 秒超时）                                            |
| `JSONUtil.toJsonStr(...)`                   | `cn.hutool.json.JSONUtil#toJsonStr`                                                         | 订单对象 → JSON 字符串                                       |
| `stringRedisTemplate`                       | 本类字段 :51 = `org.springframework.data.redis.core.StringRedisTemplate`                        | 所有 Redis 操作的入口                                        |
| `stringRedisTemplate.scan(ScanOptions...)`  | `org.springframework.data.redis.core.StringRedisTemplate#scan`                              | 游标分批遍历匹配 key（等价 `SCAN 游标 MATCH seckill:reservation:* COUNT 100`） |
| `.opsForHash().entries(reservationKey)`     | `HashOperations#entries`                                                                    | `HGETALL`：取出流水的六个字段                                  |
| `ScanOptions` / `Cursor<String>`            | `org.springframework.data.redis.core.ScanOptions` / `...Cursor`                             | SCAN 的配置（match/count）与游标迭代器                            |
| `opsForHash().increment(...)`               | `org.springframework.data.redis.core.HashOperations#increment`                              | `HINCRBY`，retryCount +1                               |
| `opsForHash().put(...)`                     | `HashOperations#put`                                                                        | `HSET`；`Instant` = `java.time.Instant#getEpochSecond` |
| `releaseRedisReservation(...)`              | `SeckillReconciliationTask#releaseRedisReservation`                                         | 本类 :209-219 → 执行 `seckill_rollback.lua`               |
| `opsForSet().add(...)`                      | `org.springframework.data.redis.core.SetOperations#add`                                     | `SADD` 补回一人一单名单                                       |
| `.delete(reservationKey)`                   | `RedisTemplate#delete`                                                                      | `DEL` 流水结案                                            |
| `orderId / userId / voucherId / reservedAt` | `SeckillReconciliationTask#parseLong`（本类 :221-230）                                          | 前文从流水 Hash 解析得到                                       |

**5.3 `shouldRetry()`：重投限频**（`com.hmdp.listener.SeckillReconciliationTask#shouldRetry()`，`SeckillReconciliationTask.java:200-207`）

```java
// 文件：src/main/java/com/hmdp/listener/SeckillReconciliationTask.java
// 类：com.hmdp.listener.SeckillReconciliationTask
// 方法：private boolean shouldRetry(Map<Object, Object> fields, long reservedAt)（:200-207）
private boolean shouldRetry(Map<Object, Object> fields, long reservedAt) {
    long now = Instant.now().getEpochSecond();       // java.time.Instant#getEpochSecond
    if (now - reservedAt < staleSeconds) {           // staleSeconds 字段（:64-65），默认 30 秒，yaml 可配
        return false;                                // 还没超过等待窗口：正常消费者可能马上就落库了
    }
    Long lastRetryAt = parseLong(fields.get(LAST_RETRY_AT_FIELD));
    // 本类方法 #parseLong（:221-230）；LAST_RETRY_AT_FIELD 常量 = "lastRetryAt"（:39）
    return lastRetryAt == null || now - lastRetryAt >= retryIntervalSeconds;
    // retryIntervalSeconds 字段（:68-69），默认 60 秒；从未重投 或 距上次重投已超间隔 → 允许重投
}
```

**5.4 `reconcileActiveOrderCounts()`：数量对账**（`com.hmdp.listener.SeckillReconciliationTask#reconcileActiveOrderCounts()`，`SeckillReconciliationTask.java:174-198`）

```java
// 文件：src/main/java/com/hmdp/listener/SeckillReconciliationTask.java
// 类：com.hmdp.listener.SeckillReconciliationTask
// 方法：private void reconcileActiveOrderCounts()（:174-198）
// key / voucherId 来自外层 SCAN seckill:order:* 游标循环（:181-183）：
String key = cursor.next();
String voucherIdText = key.substring(RedisConstants.SECKILL_ORDER_KEY.length());
long voucherId = Long.parseLong(voucherIdText);

Long redisUsers = stringRedisTemplate.opsForSet().size(key);
// StringRedisTemplate#opsForSet() → SetOperations#size（SCARD 名单人数）
Long dbActiveOrders = voucherOrderMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
        .eq(VoucherOrder::getVoucherId, voucherId)          // WHERE voucher_id = ?
        .ne(VoucherOrder::getStatus, 4));                   // AND status != 4：只统计未取消订单
// VoucherOrderMapper#selectCount（MyBatis-Plus BaseMapper）；LambdaQueryWrapper = com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper
// 等价 SQL：SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ? AND status != 4
if (redisUsers != null && dbActiveOrders != null
        && redisUsers.longValue() != dbActiveOrders.longValue()) {
    log.warn("秒杀对账不一致 voucherId={}, redisUsers={}, dbActiveOrders={}",
            voucherId, redisUsers, dbActiveOrders);         // 只告警：无法安全推断该加还是该减
}
```

**5.5 执行器配置与启动**（`com.hmdp.config.XxlJobConfig#xxlJobExecutor()`，`XxlJobConfig.java:41-53`）

```java
// 文件：src/main/java/com/hmdp/config/XxlJobConfig.java
// 类：com.hmdp.config.XxlJobConfig（:14-15，@Configuration）
// 方法：public XxlJobSpringExecutor xxlJobExecutor()（:41-53）
// 返回类型：com.xxl.job.core.executor.impl.XxlJobSpringExecutor
// 各 setter 参数来自本类 @Value 字段（:17-39），最终来自 application.yaml:41-52
@Bean(initMethod = "start", destroyMethod = "destroy")   // 随 Spring 启动 / 停止执行器
public XxlJobSpringExecutor xxlJobExecutor() {
    XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
    executor.setAdminAddresses(adminAddresses);   // 调度中心地址（yaml：xxl.job.admin.addresses）
    executor.setAccessToken(accessToken);         // 通信令牌（yaml：xxl.job.admin.accessToken，默认 default_token）
    executor.setAppname(appName);                 // 执行器名 neargo-executor，要和 Admin 页面一致
    executor.setAddress(address);                 // 执行器地址（可选，自动推导）
    executor.setIp(ip);                           // 执行器 IP
    executor.setPort(port);                       // 执行器端口 9999
    executor.setLogPath(logPath);                 // 任务执行日志目录
    executor.setLogRetentionDays(logRetentionDays);   // 日志保留天数
    return executor;
}
```

这段代码的运行后果见 2.2 节（启动注册四步）；Admin 侧配置见 2.3 节：`AppName = neargo-executor`、`JobHandler = seckillReconcileJob`、Cron 自定（如每分钟一次）。

**5.6 一次完整执行的时间线（把 5.1-5.4 串起来）**

以 11:20:00 触发、扫到 3 条流水为例：

```text
11:20:00  Admin Cron → /run → MethodJobHandler.invoke → reconcile()

11:20:00  SCAN seckill:reservation:*（#repairReservations，:98-102）扫到 3 条：
  ① reservation:1001:2   orderId=1758…001，DB 查无此单
     reservedAt=11:18:00（超 30s 窗口）、lastRetryAt=11:19:00（距 60s）
     → 分支一：syncSend(seckill-order-topic, 原 ID 订单, 3000)
     → HINCRBY retryCount 1→2；HSET lastRetryAt=11:20:00               ✅ 修完
  ② reservation:1002:3   DB 订单 status=4（已取消）
     → 分支二：#releaseRedisReservation → seckill_rollback.lua
       SISMEMBER seckill:order:1002 3 = 1 → SREM + stock:1002 +1 + DEL 流水 → 返回 1  ✅ 修完
  ③ reservation:1003:4   DB 订单 status=1（已落库）
     → 分支三：SADD seckill:order:1003 4 + DEL 流水                     ✅ 修完

11:20:00  SCAN seckill:order:*（#reconcileActiveOrderCounts）逐个 SCARD vs COUNT(status!=4)
         全一致 → 无 log.warn
11:20:00  hasFailure=false → 正常返回 → pushTriggerCallback(handleCode=200)

—— 失败路径（假设 ② 执行时 Redis 超时）——
11:20:00  ② catch → hasFailure=true（本轮 ①③ 照样修完）
11:20:00  数量对账照跑 → 返回 true → 抛 IllegalStateException → callback 500
11:20:3x  Admin 失败监控扫到 500 → 按任务配置重试，立即重发 /run
11:20:3x  SCAN 又扫到 ②（脚本没成功、流水还在——这就是幂等的价值）
          → 再执行释放 → 成功 → callback 200
```

#### 6. 为什么用 XXL-JOB（选型对比）

| 方案                        | 决定性因素                                         | 判定  |
| ------------------------- | --------------------------------------------- | --- |
| `@Scheduled`              | 单机简单，但无日志、无重试，多实例直接重复执行                       | 淘汰  |
| `@Scheduled` + Redisson 锁 | 只解决「谁执行」，重试、告警还要自建                            | 淘汰  |
| Quartz                    | 调度表建在业务库，且每次触发都抢 `QRTZ_LOCKS` 行锁，触发频繁时 DB 压力大 | 偏重  |
| **XXL-JOB**               | 调度/执行分离，自带运行日志、失败重试、路由                        | 采用  |

Redisson 锁只回答「谁执行」；XXL-JOB 还回答「何时执行、结果如何、失败后怎么办」。

**为什么淘汰 Quartz（重点展开）**

Quartz 是 Java 里最老牌的调度框架：支持 Cron、任务持久化到数据库、集群部署，功能其实不少。不选它的根本原因是——**它是「调度库」，不是「调度平台」**：

1. **调度数据落在业务库**：Quartz 集群模式必须用 JDBC JobStore，要在数据库里建 `QRTZ_*` 十几张表——调度数据和业务数据混在一个库，权限、备份、运维全部耦合；
2. **触发成本随次数线性增长**：集群模式下每次触发都要在 `QRTZ_LOCKS`、`QRTZ_TRIGGERS`、`QRTZ_FIRED_TRIGGERS` 上加行锁、读写状态，任务多、频率高时数据库先扛不住；
3. **只有触发，没有治理**：没有管理台——任务的增删改、暂停、手动执行要么改代码要么自建界面；执行日志、失败重试、告警、路由策略也统统要自己搭。而这些恰好是对账任务最需要的（谁失败了、为什么失败、重试了几次）；
4. **集群配置和排查门槛高**：实例 ID、acquisition、misfire 策略等一堆概念，出问题排查成本高。

两边能力对照：

| 能力 | Quartz | XXL-JOB |
| --- | --- | --- |
| Cron 触发 | ✅ | ✅ |
| 调度数据 | 业务库建 `QRTZ_*` 表 | 独立 `xxl_job` 库 + 独立调度中心 |
| 管理台（增删改 / 启停 / 手动执行） | ❌ 自己写 | ✅ 自带 |
| 执行日志 / 失败重试 / 告警 | ❌ 自己写 | ✅ 自带 |
| 路由 / 故障转移 / 分片广播 | 需自己实现 | ✅ 页面配置 |
| 触发锁代价 | 每次触发抢多表行锁 | 每 5 秒抢一行锁、毫秒级（见第 7 节） |

一句话：**我们要的是一套「能看、能重试、能运维」的调度平台，不是一个需要自己伺候的调度库**——这就是选 XXL-JOB 不选 Quartz 的根本原因。

#### 7. 边界（面试追问）

| 追问                            | 回答                                                                                                                                                                                                                                                            |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 为什么不能只靠 MQ 消费重试？              | MQ 重试的前提是消息已进 Broker；预扣后、入 Broker 前崩溃的消息消费者看不到，只能靠 reservation 扫描发现                                                                                                                                                                                           |
| XXL-JOB Admin 挂了会怎样？          | 调度停摆、在线执行器注册信息过期，对账中断；恢复后按 Cron 继续。业务链路不受影响，执行器只注册/执行，不依赖 Admin 在线跑业务                                                                                                                                                                                         |
| 换一台执行器会重复执行吗？                 | 单次调度按路由策略只发一台；失败重试、故障转移可能换机器再跑，所以补偿动作本身必须幂等（重投用原 ID、释放用 Lua 判断）                                                                                                                                                                                               |
| xxl_job 表这么多，会像 Quartz 那样抢锁吗？ | 也会用一把 DB 锁，但只有 `xxl_job_lock` 的 `schedule_lock` 一行（`SELECT ... FOR UPDATE`），且只在每 5 秒一次的扫描事务里持有毫秒级：查到期任务 + 更新 `trigger_next_time`，提交后才丢给触发线程池，执行和 callback 不持锁。`xxl_job_log` 纯追加、`xxl_job_registry` 各执行器各写各的，不参与锁竞争。Quartz 是每次触发都在 `QRTZ_LOCKS` + 触发器表上加锁，量级不同 |
| 为什么用 SCAN 不用 KEYS？            | KEYS 会阻塞 Redis；SCAN 渐进遍历。数据量继续增长后应按时间/券分片对账                                                                                                                                                                                                                   |
| 为什么不用 Quartz？                   | 它是「调度库」不是「调度平台」：调度表建在业务库、每次触发抢 `QRTZ_*` 多表行锁，管理台 / 执行日志 / 失败重试 / 告警都要自建；完整对比见第 6 节 |
| XXL-JOB 会重复执行吗？               | 重试、故障转移都可能重复触发，所以补偿动作本身必须幂等（重投用原 ID、释放用 Lua 判断）                                                                                                                                                                                                               |
| 这是分布式事务吗？                     | 不是。允许短暂不一致，用消息、幂等、对账逐步收敛，属于最终一致性                                                                                                                                                                                                                              |
| 补偿能无限重试吗？                     | 不能。当前记录了 retryCount/lastRetryAt，但还缺最大次数、告警和人工入口                                                                                                                                                                                                               |
| 验证到什么程度？                      | 执行器注册、JobHandler、三分支补偿代码已接入并通过构建；**Admin 跨进程调度已端到端验证**（定时触发成功、callback `handleCode=200`），但**故障转移、真实数据下的补偿场景还没验证**，不能写成生产验证完成                                                                                                                                  |

---


### 3.2 补偿账本：把"事后账本"落成一张表（有界重试）

*对应简历第 3 条 · 补偿账本部分（原文档未覆盖，本次新增）*

> 原文档"第五问"讲的是**谁在什么时候触发对账**（XXL-JOB）、**三个分支各做什么**（重投 / 释放 / 补名单）、**失败怎么让 XXL-JOB 重试**。本节只补最后半句：**"异常任务失败重试并落库补偿账本"里那张"账本"是什么表、什么状态机、怎么保证重复记账不写坏。**
>
> 一句话定位：**Redis 的 `seckill:reservation:*` 是快路径线索（会被 TTL 淘汰、可能被误删）；`tb_task_compensation` 是慢路径账本（唯一键定位、有重试次数、有终态、有错误原因，供审计与人工接手）。**

#### 1. 这条在说什么（为什么光有 XXL-JOB 定时扫描还不够，还需要一张账本表）

先看只有"Redis 线索 + 定时扫描"时，缺的是什么：

| 只有 Redis 线索的问题 | 后果 | 账本怎么补 |
|---|---|---|
| `retryCount` / `lastRetryAt` 只在 Hash 里（`ReservationRequest.java:14-15`），随 `seckill:reservation:*` 一起被 `expire 604800`（7 天，`seckill.lua:50`）淘汰 | 线索一到期/被误删，"这单补偿过几次、为什么失败"**没有任何地方能回答** | 账本一个业务键一行（`uk_biz(biz_type, biz_id)`），**订单维度**永久留痕 |
| 重投失败只打日志（`RepostNode.java:57-58`、`SeckillReconciliationTask.java:123-125`） | 日志会被轮转、无法按"哪些单没救回来"捞出来 | `status = 3`（终态失败）让"放弃自动重试的单"变成一条**可 SQL 查询**的记录 |
| "该不该继续重试"没有终态 | 一条永远不成功的记录会被每分钟的 Cron 一直重投，无人知道 | `retryCount` 达上限 → `STATUS_FAILED` + `lastError`，**从"永远重试"变成"转人工"** |
| 失败原因散落在 Redis Hash / 日志 | 交接人工时说不清"卡在哪一步" | `lastError` 落库（varchar 512，`hmdp.sql:296`） |

关键是**职责分离**：账本**不参与重投决策**——"是否重投""重投了几次"仍然只读 Redis（`RepostNode.java:52` 的 `currentRetryCount(request)` 只读 `ReservationRequest.RETRY_COUNT_FIELD`，全仓库没有任何一处回读账本、也没有任何一处对账本做 `selectList` 查询，`TaskCompensationMapper.java:9` 只有 `BaseMapper` 的空接口）。账本的作用是**给出一个可解释、可查询、可交接的终态**，而不是让重试更聪明。

一句话背下来：**定时扫描解决"什么时候发现"，规则树解决"发现后修什么"，账本解决"修没修好、试了几次、什么时候放弃的"。**

#### 2. 表结构与字段语义（tb_task_compensation，字段逐个解释，标注来源文件:行号）

表建模与索引来自 `hmdp.sql:289-301`（与增量脚本 `docs/sql/task_compensation.sql:3-15` 内容一致）：

```sql
CREATE TABLE IF NOT EXISTS `tb_task_compensation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `biz_type` varchar(32) NOT NULL COMMENT '业务类型：ORDER_CREATE=秒杀订单落库补偿',
  `biz_id` bigint(20) NOT NULL COMMENT '业务ID（如订单ID）',
  `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '状态：0待处理 1已成功 2重试中 3终态失败(人工)',
  `retry_count` int(11) NOT NULL DEFAULT '0' COMMENT '重试次数',
  `next_execute_time` datetime DEFAULT NULL COMMENT '下次执行时间',
  `last_error` varchar(512) DEFAULT NULL COMMENT '最近一次错误',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz` (`biz_type`,`biz_id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact COMMENT='补偿任务账本';
```

实体字段与 Java 常量（`TaskCompensation.java`）：

| 字段 / 常量 | 类型 | 含义与真实行为 | 源码位置 |
|---|---|---|---|
| `serialVersionUID` | `long` | 序列化版本号，与业务无关 | `TaskCompensation.java:21` |
| `id` | `Long` | 主键，`IdType.AUTO` 自增（SQL 侧 `AUTO_INCREMENT`） | `TaskCompensation.java:36-37` / `hmdp.sql:290` |
| `bizType` | `String(32)` | 业务类型；当前**只有一种取值**：`BIZ_TYPE_ORDER_CREATE = "ORDER_CREATE"` | `TaskCompensation.java:24`、`:40` / `hmdp.sql:291` |
| `bizId` | `Long` | 业务 ID，实际传的是**订单 ID**（三个节点都传 `context.getOrderId()`） | `TaskCompensation.java:43` / `hmdp.sql:292` |
| `status` | `Integer(tinyint)` | 0 待处理（`STATUS_PENDING`）/ 1 已成功（`STATUS_SUCCESS`）/ 2 重试中（`STATUS_RETRYING`）/ 3 终态失败转人工（`STATUS_FAILED`） | `TaskCompensation.java:27-33`、`:46` / `hmdp.sql:293` |
| `retryCount` | `Integer(int)` | 重投次数；由 Redis 的 `retryCount` 读入后 +1 写入（`RepostNode.java:75-82`），**不是**从账本自身累加 | `TaskCompensation.java:49` / `hmdp.sql:294` |
| `nextExecuteTime` | `LocalDateTime(datetime)` | 下次执行时间；写入值 = `now + retryIntervalSeconds`（默认 60 秒）；闭环或终态失败时置 `null` | `TaskCompensation.java:52` / `hmdp.sql:295` |
| `lastError` | `String(512)` | 最近一次错误；仅"达到上限转人工"时写入（`RepostNode.java:55-56`），重投成功与闭环都置 `null` | `TaskCompensation.java:55` / `hmdp.sql:296` |
| `createTime` | `LocalDateTime(timestamp)` | 创建时间，SQL 默认 `CURRENT_TIMESTAMP`；实体上**没有** MyBatis-Plus 自动填充注解，插入时为 `null`，由数据库补默认值 | `TaskCompensation.java:58` / `hmdp.sql:297` |
| `updateTime` | `LocalDateTime(timestamp)` | 更新时间，SQL `ON UPDATE CURRENT_TIMESTAMP` 自动刷新；实体同样没有自动填充 | `TaskCompensation.java:61` / `hmdp.sql:298` |
| `uk_biz(biz_type, biz_id)` | 唯一键 | **幂等的物理保证**：同一业务类型 + 同一业务 ID 只允许一行 | `hmdp.sql:300` |

两个必须记住的坑：

1. **`STATUS_PENDING = 0` 定义了但从未被写入**（`TaskCompensation.java:27`）。用 `status=0` 去捞"待处理"账本，一条也捞不到——`markRetrying` 首次 `insert` 就直接落 `STATUS_RETRYING`（`TaskCompensationRecorder.java:37-43`）。建表 SQL 里 `status DEFAULT '0'`（`hmdp.sql:293`）只是兜底默认值。
2. **这两个字段不是"任务调度表"**：账本里没有"执行者/节点/重试间隔策略"等列，`nextExecuteTime` 也**没有任何扫描器消费它**（全仓库对账本只有 `insert` / 两个条件的 `update` / `selectOne ... limit 1`）——对账任务的触发时间完全由 XXL-JOB 的 Cron 决定。说"账本负责下次什么时候执行"是错的，它只**记录**期望的下次时间。

#### 3. 状态机：RETRYING → SUCCESS / FAILED，以及 retryCount / nextExecuteTime / lastError 的作用

```text
                   markRetrying（重投成功）
  （行不存在） ─────────insert──────────→  STATUS_RETRYING = 2
                                              │        ▲
                                              │        │ markRetrying（再次重投）
                    markSuccess（订单已落库 /      │        │ retryCount 覆盖为最新值
                    预扣已释放）                   ▼        │
                                          STATUS_SUCCESS = 1（终态，ne 条件 → 不覆盖）

  retryCount >= maxRetryCount（默认 5）
  且本轮仍扫到该预扣记录 ──markFailed──→  STATUS_FAILED = 3（终态，转人工）

  STATUS_PENDING = 0：实体里定义，但源码中从未写入（不是可达状态）
```

| 字段 | 谁写 | 精确算法（照源码背） | 语义 |
|---|---|---|---|
| `status` | `markRetrying` / `markSuccess` / `markFailed` | 三个方法各自 `set(status, ...)`（`TaskCompensationRecorder.java:40`、`:49`、`:61`、`:71`） | 只作**终态标记**：1 或 3 表示这条补偿线索已"结案"，2 表示还在自动重试过程中 |
| `retryCount` | 只有 `markRetrying` | 入参来自 `RepostNode.currentRetryCount(request)`（读 Redis `retryCount` + 缺失按 0，`RepostNode.java:87-90`）再 `+1`（`:75`），因此账本里是 **1,2,3… 递增的最新值**，`update` 是**覆盖**不是累加 | 用于审计"这单自动救过几次"；**上限判断不读它** |
| `nextExecuteTime` | `markRetrying` 写；`markSuccess` / `markFailed` 置 `null` | `LocalDateTime.now().plusSeconds(retryIntervalSeconds)`，`retryIntervalSeconds ← seckill.reconcile.retry-interval-seconds`（YAML 默认 60，`application.yaml:42`） | 落库留痕的"理论下次重投时间"；与实际限频字段 Redis `lastRetryAt`（`RepostNode.java:97-98`）是两套东西，**代码中未确认有消费该列的逻辑** |
| `lastError` | `markRetrying` / `markFailed` 写；`markSuccess` 置 `null` | 重投成功时传的是 `null`（`RepostNode.java:82`）→ **成功一次会把历史错误清空**；转人工时写 `"自动重投超过上限 " + maxRetryCount + " 次"`（`RepostNode.java:55-56`） | 只保留**最近一次**错误，不是错误流水；给人工看"为什么放弃" |

状态机的三个要点（面试时最容易说错）：

- **没有从 SUCCESS / FAILED 回到 RETRYING 的路径吗？** 有，但只对 SUCCESS 有保护：`markSuccess` 带 `ne(status, SUCCESS)`（`TaskCompensationRecorder.java:60`），而 `markRetrying` 的 `update` 条件**只有 `bizType + bizId`**（`:46-52`），`markFailed` 同样只有两个条件（`:68-73`）。所以语义上：**成功是终态、不可覆盖；失败可以被后续的 `markRetrying` 覆盖回重试中**（同一条预扣记录再次被重投时）。
- **SUCCESS 的两种来源**：订单已落库 → `RepairNode`（`RepairNode.java:31-32`）；订单已取消、预扣已释放 → `ReleaseNode`（`ReleaseNode.java:52-53`）。注意 `ReleaseNode` 里 `ROLLBACK_SCRIPT` 返回 `null` 会先抛异常（`:49-51`），异常一旦抛出，`markSuccess` **不会**执行——不会出现"没释放却记成功"。
- **FAILED 只是个状态，不会删 Redis 线索**：`RepostNode` 达到上限时只记账本、置 `FAILED_MANUAL` 并 `return null`（`RepostNode.java:55-61`），`seckill:reservation:*` 仍在（最长留到 `seckill.lua:50` 的 7 天 TTL），因此下一轮 Cron 仍会扫到它并**再次** `markFailed`（见第 5 节）。

#### 4. 核心代码（TaskCompensationRecorder 四个方法的真实实现，标注 文件:行号；说明每个方法在哪个 Node 的哪个分支被调用）

`TaskCompensationRecorder`（`src/main/java/com/hmdp/listener/reconcile/TaskCompensationRecorder.java`）只有 4 个方法，加上一个注入的 Mapper 与一个配置：

| 方法 | 行号 | 签名 | 一句话职责 | 调用点（分支） |
|---|---|---|---|---|
| `markRetrying` | `:34-53` | `void markRetrying(String bizType, Long bizId, int retryCount, String lastError)` | 不存在则 `insert` 一行 `RETRYING`；存在则 `update` 覆盖 `status/retryCount/nextExecuteTime/lastError` | `RepostNode.java:81-82` → **重投分支、MQ 发送成功之后** |
| `markSuccess` | `:56-64` | `void markSuccess(String bizType, Long bizId)` | 闭环为 `SUCCESS`，清空 `nextExecuteTime` 与 `lastError`；带 `ne(SUCCESS)` 幂等条件 | `RepairNode.java:32`（已落库分支）/ `ReleaseNode.java:53`（已取消分支，回滚脚本执行成功后） |
| `markFailed` | `:67-74` | `void markFailed(String bizType, Long bizId, String lastError)` | 标 `FAILED`，清空 `nextExecuteTime`，写入 `lastError` | `RepostNode.java:55-56` → **重投分支开头、`retryCount >= maxRetryCount` 时** |
| `findByBiz` | `:76-81` | `private TaskCompensation findByBiz(String bizType, Long bizId)` | `selectOne` 按 `bizType + bizId` 查一行，`.last("limit 1")` 兜底 | 只被 `markRetrying` 调用（`:35`）——**其余三个方法都不回读** |

**4.1 `markRetrying`（`:33-53`）——"先查后写"的两条路径**

```java
34      public void markRetrying(String bizType, Long bizId, int retryCount, String lastError) {
35          TaskCompensation existing = findByBiz(bizType, bizId);
36          if (existing == null) {
37              taskCompensationMapper.insert(new TaskCompensation()
38                      .setBizType(bizType)
39                      .setBizId(bizId)
40                      .setStatus(TaskCompensation.STATUS_RETRYING)
41                      .setRetryCount(retryCount)
42                      .setNextExecuteTime(LocalDateTime.now().plusSeconds(retryIntervalSeconds))
43                      .setLastError(lastError));
44              return;
45          }
46          taskCompensationMapper.update(null, new LambdaUpdateWrapper<TaskCompensation>()
47                  .eq(TaskCompensation::getBizType, bizType)
48                  .eq(TaskCompensation::getBizId, bizId)
49                  .set(TaskCompensation::getStatus, TaskCompensation.STATUS_RETRYING)
50                  .set(TaskCompensation::getRetryCount, retryCount)
51                  .set(TaskCompensation::getNextExecuteTime, LocalDateTime.now().plusSeconds(retryIntervalSeconds))
52                  .set(TaskCompensation::getLastError, lastError));
53      }
```

- 首次重投 → `insert` 一行（`status=2`，`retryCount=1`）；第二次及以后 → 命中 `findByBiz`，走 `update` **覆盖** `retryCount`。
- `retryIntervalSeconds` 来自 `@Value("${seckill.reconcile.retry-interval-seconds:60}")`（`:29-31`），与 `RepostNode` 里控制**真实限频**的同名配置（`RepostNode.java:43-44`，实际比较对象是 Redis 的 `lastRetryAt`，`:97-98`）是同名不同源的两套用法。
- 传入的 `lastError` 在重投成功路径上是 `null`（`RepostNode.java:82`），因此**一旦重投成功，账本上的旧错误就被清空**——账本记的是"当前状态"，不是错误历史。

**4.2 `markSuccess`（`:55-64`）——闭环 + 幂等条件**

```java
56      public void markSuccess(String bizType, Long bizId) {
57          taskCompensationMapper.update(null, new LambdaUpdateWrapper<TaskCompensation>()
58                  .eq(TaskCompensation::getBizType, bizType)
59                  .eq(TaskCompensation::getBizId, bizId)
60                  .ne(TaskCompensation::getStatus, TaskCompensation.STATUS_SUCCESS)
61                  .set(TaskCompensation::getStatus, TaskCompensation.STATUS_SUCCESS)
62                  .set(TaskCompensation::getNextExecuteTime, null)
63                  .set(TaskCompensation::getLastError, null));
64      }
```

- 关键在 `:60` 的 `ne(status, SUCCESS)`：**已经成功的行不会再被写一次**，`update_time` 不会因重复对账而刷新。
- 对"从未进过账本"的订单（抢券后正常落库、从没被重投）：`WHERE biz_type=? AND biz_id=? AND status<>1` 匹配 0 行，**影响 0 行且不报错**——这是设计内行为，`RepairNode.java:31` 的注释写了这句原话。

**4.3 `markFailed`（`:66-74`）——转人工终态**

```java
67      public void markFailed(String bizType, Long bizId, String lastError) {
68          taskCompensationMapper.update(null, new LambdaUpdateWrapper<TaskCompensation>()
69                  .eq(TaskCompensation::getBizType, bizType)
70                  .eq(TaskCompensation::getBizId, bizId)
71                  .set(TaskCompensation::getStatus, TaskCompensation.STATUS_FAILED)
72                  .set(TaskCompensation::getNextExecuteTime, null)
73                  .set(TaskCompensation::getLastError, lastError));
74      }
```

- **注意这里没有 `ne(status, FAILED)` 之类的排除条件**（对比 `:60`）：所以只要该预扣记录还被扫到、`retryCount` 又仍 ≥ 上限，每轮对账都会重写一次 `status=3 / last_error / update_time`（见第 5、6 节）。

**4.4 `findByBiz`（`:76-81`）——存在性判断**

```java
76      private TaskCompensation findByBiz(String bizType, Long bizId) {
77          return taskCompensationMapper.selectOne(new LambdaQueryWrapper<TaskCompensation>()
78                  .eq(TaskCompensation::getBizType, bizType)
79                  .eq(TaskCompensation::getBizId, bizId)
80                  .last("limit 1"));
81      }
```

- `private`，只服务 `markRetrying` 的"插入 or 更新"二选一；`.last("limit 1")` 防止唯一键异常时 `selectOne` 抛 `TooManyResultsException`。
- `TaskCompensationMapper` 是空接口（`TaskCompensationMapper.java:9`：`extends BaseMapper<TaskCompensation>`），**没有任何自定义 SQL**。

**4.5 三个调用点的时机（顺序很重要）**

| 调用点 | 所在分支 | 精确时机 | 源码 |
|---|---|---|---|
| `RepostNode.markFailed` | 订单查不到（未落库）+ `retryCount >= maxRetryCount` | 进来先判断上限，**先记账再置 `FAILED_MANUAL`**，本轮不投消息 | `RepostNode.java:52-61` |
| `RepostNode.markRetrying` | 订单查不到 + 未到重投窗口 → `SKIPPED`（不记账） | **`syncSend` 成功之后**才记账：Redis `retryCount` +1（`:76-77`）、`lastRetryAt` 更新（`:78-79`）、再写账本（`:80-82`）、最后置 `REPOSTED`（`:83`） | `RepostNode.java:62-84` |
| `ReleaseNode.markSuccess` | 订单存在且 `status = 4`（已取消，`OrderLookupNode.java:40-42`） | 回滚脚本返回非 `null` 之后 | `ReleaseNode.java:40-54` |
| `RepairNode.markSuccess` | 订单存在且 `status ≠ 4`（已落库，`OrderLookupNode.java:43`） | `SADD` 补名单 + `DEL` 预扣记录之后 | `RepairNode.java:27-33` |

> 顺序的含义：**"消息真的发出去了"才记 `RETRYING`**，`syncSend` 抛异常会先抛出去（`RepostNode.java:70-74`），账本不会出现"记了重投但其实没投"的脏记录；反过来，`markRetrying` 自身抛异常（如唯一键冲突）会让本条记录计入本轮失败（`SeckillReconciliationTask.java:94-97`），最终让整轮任务失败并被 XXL-JOB 重试（`:61-64`）。

#### 5. 有界重试：为什么必须有上限、超过上限会怎样（以真实常量/配置为准，不要编造）

**上限是多少、写在哪：**

| 项 | 真实值 | 位置 |
|---|---|---|
| 自动重投次数上限 | `@Value("${seckill.reconcile.max-retry-count:5}")` → 代码内默认 **5** | `RepostNode.java:46-48` |
| YAML 绑定 | `seckill.reconcile.max-retry-count: ${SECKILL_RECONCILE_MAX_RETRY_COUNT:5}` | `application.yaml:43` |
| 判断表达式 | `if (retryCount >= maxRetryCount)`（`retryCount` 来自 Redis 的 `retryCount` 字段，缺失按 0） | `RepostNode.java:52-53`、`:87-90` |
| 重投间隔（限频） | `seckill.reconcile.retry-interval-seconds`，默认 **60** 秒；另有 `stale-seconds` 默认 **30** 秒的"刚发消息等待窗口" | `RepostNode.java:38-44`、`application.yaml:41-42` |
| 是否指数退避 | **没有**：只有"固定 60 秒最小间隔 + 固定上限 5 次"（源码中未发现退避/抖动逻辑） | `RepostNode.java:92-99` |

为什么必须有上限（三条，都能落到源码）：

1. **Redis `retryCount` 会一直被 +1 却永远不会自己停**（`RepostNode.java:76-77`）。若订单因为业务性原因永远不可能落库（例如消费端 user+voucher 校验必然 return、订单数据被人工删掉），没有上限就是**每分钟投一条、无限投到 `seckill:reservation:*` 7 天 TTL 到期**（`seckill.lua:50`）——MQ 里持续产生重复消息、下游消费端持续白跑。
2. **没上限 = 没有"放弃"的语义**，也就没有交接人工的入口。`retryCount >= maxRetryCount` 是唯一把状态从"还在自动救"翻成"必须人工看"的开关（`RepostNode.java:53-61`）。
3. **有上限才能让账本有终态**：`STATUS_FAILED` 是账本状态机里唯一的"未被自动收敛，等人"的终态（`TaskCompensation.java:32-33`）。

超过上限会发生什么（逐条，全部来自 `RepostNode.java:53-61`）：

1. `taskCompensationRecorder.markFailed(BIZ_TYPE_ORDER_CREATE, orderId, "自动重投超过上限 " + maxRetryCount + " 次")` → 账本 `status=3`，`nextExecuteTime=null`，`lastError` 写明原因（`:55-56`）；
2. `log.warn("秒杀订单补偿超过最大重试次数，转人工处理 orderId={}, retryCount={}", ...)`（`:57-58`）；
3. `context.setOutcome(RepairOutcome.FAILED_MANUAL)` 并 `return null`（`:59-60`）——本轮**不投消息**；
4. 结果一路传到终点 `ReconcileEndNode`（`ReconcileEndNode.java:14-16`）返回，任务侧 `case FAILED_MANUAL` 打 `log.error`（`SeckillReconciliationTask.java:123-125`）；
5. **本轮不算失败**：`FAILED_MANUAL` 不会把 `hasFailure` 置 `true`，因此不会触发"抛异常让 XXL-JOB 重试"（异常只在单条处理抛异常或扫描失败时产生，`SeckillReconciliationTask.java:94-97`、`:99-102`），避免"转人工"又被无限重试。

**必须说清的边界（这是"有界重试"最容易被追问穿的地方）**：达到上限后**不删 Redis 预扣记录**，而 Redis 里的 `retryCount` 已经 ≥ 5，于是下一个 Cron 周期（默认每分钟）`SCAN` 仍会扫到它，一进 `RepostNode` 就再次满足 `retryCount >= maxRetryCount` → **每轮都重新 `markFailed` 一次**（`update_time` 持续刷新、`log.error` 每轮一条），直到订单最终落库/被取消（走 `markSuccess`）或 `seckill:reservation:*` 到期（`seckill.lua:50`，7 天）。所以严格表述是：**"有界"限定的是"自动重投次数"（最多 5 次），不是"账本写入次数"**——这一点源码中未确认有额外的"终态跳过"判断（`RepostNode` 里没有"若账本已 FAILED 则跳过"的分支）。

#### 6. 幂等性：重复记账为什么不会写坏（重点：更新条件里对 SUCCESS 的排除、findByBiz 的存在性判断）

| 机制 | 位置 | 它挡住了什么 | 没挡住什么 |
|---|---|---|---|
| **唯一键 `uk_biz(biz_type, biz_id)`** | `hmdp.sql:300`（增量脚本 `docs/sql/task_compensation.sql:14`） | 同一订单最多一行账本；并发/重放都不可能产生两行"同一单的账本" | 冲突时会**抛异常**（不是静默忽略），本轮该条记录计为失败并让整轮任务失败重试（`SeckillReconciliationTask.java:94-97`、`:61-64`）——这是"暴露"而不是"隐藏" |
| **`findByBiz` 先查、存在即改**（`:35-45`） | `TaskCompensationRecorder.java:76-81` | 同一订单第二次重投走 `update`，不会又 `insert` 一行；把唯一键的冲突面从"每次都撞"缩小到"两个线程同时首投" | `findByBiz` 只按 `bizType + bizId` 查、不按状态查，所以**它挡不住"已经 SUCCESS 的行被再次 `markRetrying` 覆盖"**（`markRetrying` 的 `update` 没有状态条件，`:46-52`） |
| **`markSuccess` 的 `ne(status, SUCCESS)`** | `TaskCompensationRecorder.java:60` | 每分钟重复对账时，已闭环的行不会被反复写；`update_time` 保留首次闭环时间，可用它统计"真实闭环耗时" | 影响 0 行时**不报错**，只说明"这单从没进过账本"，调用方无法（也不需要）区分 |
| **`update` 全部以 `biz_type + biz_id` 为条件** | `:47-48`、`:58-59`、`:69-70` | 记账天然按业务主键定位，不依赖自增 `id`（调用方拿不到 `id`，也不需要） | 影响 0 行是合法结果：`markSuccess` 对"从未重投过的订单"就是 0 行（`RepairNode.java:31` 注释原地说明） |
| **记账在"动作成功之后"** | `RepostNode.java:70-82`、`ReleaseNode.java:49-53`、`RepairNode.java:27-32` | 不会出现"消息没发出去却记 `RETRYING`""预扣没释放却记 `SUCCESS`"（回滚脚本返回 `null` 先抛异常，`:49-51`） | 反过来，"动作成功但记账失败"（如唯一键冲突）会让整轮失败重试——此时 Redis 侧已生效，重试是安全的 |

**最容易问倒的一问：重复记账为什么不会写坏？**——标准答法是分三层：

1. **物理层**：`uk_biz` 唯一键让"同一业务键两行"在数据库层面不可能发生（`hmdp.sql:300`）；
2. **应用层**：`markRetrying` 用 `findByBiz` 做存在性判断，走"插入 or 覆盖更新"（`TaskCompensationRecorder.java:35-45`）；
3. **语义层**：`markSuccess` 用 `ne(status, SUCCESS)` 把"成功"锁成终态，重复成功不产生任何副作用（`:60`）。

**顺带说清一个常见误解**：账本**不**参与"这件事是否已经处理过"的判定。`findByBiz` 只在 `markRetrying` 里用；状态判定（订单存在吗、取消了吗、重投几次了）全部来自 Redis 与 MySQL 订单表（`OrderLookupNode.java:29-43`、`RepostNode.java:52`）。所以**账本缺失也不会导致重复处理**，它是"旁路审计"，不是"幂等闸门"。

#### 7. 边界（面试追问）

| 追问 | 怎么答（可直接背） |
|---|---|
| 账本到底解决什么问题？有它和没它差在哪？ | 差在**终态与可审计性**。没有账本时，"这单补偿过几次、为什么失败、什么时候放弃的"只存在于 Redis Hash 和日志里（`ReservationRequest.java:14-15`），Redis 一丢就没、日志一滚就散。有了账本，同一个业务键（`biz_type + biz_id`）在 MySQL 里有且只有一行，`status=3` 一查就是"所有放弃自动重试的单"。它**不改变重试逻辑**，只把过程变成可查询的事实。 |
| 为什么钱要花在数据库上，不继续用 Redis 的 `retryCount`？ | 两者是**快路径 / 慢路径**的分工，不是替代关系。重投限频必须在 Redis 上做（热路径、要原子、要快，`RepostNode.java:76-79`）；账本要做的是"Redis 线索没了，问题还在"（`README.md:797`）。而且账本**不在秒杀热路径写**——只在异常补偿时由对账任务写，不会给下单链路加一次 DB 写。 |
| Redis 里的 `retryCount` 丢了会怎样？ | 这是已确认的真实边界：重投计数只读 Redis（`RepostNode.java:52`、`:87-90`），账本从不回读。Redis 数据一丢，`retryCount` 从 0 重新开始 → **最多再自动重投 5 次**；但账本里原有的 `retryCount` / `lastError` 记录还在（下一轮 `markRetrying` 会把 `retryCount` 覆盖成新值，历史值不留）。所以说"账本防丢线索"成立，"账本接管计数"不成立。 |
| 并发下两个线程同时给同一订单记账会怎样？ | `findByBiz` 都是 `null` 时两边都想 `insert`，靠 `uk_biz` 唯一键拦住第二条——后一条抛异常，被 `SeckillReconciliationTask.java:94-97` 计为本轮失败并让整轮任务失败重试（`:61-64`）。即"**冲突是显式的，不会静默重复**"。想彻底无冲突需要 `INSERT ... ON DUPLICATE KEY UPDATE`，**源码中未确认**（`TaskCompensationMapper` 是空接口，`TaskCompensationMapper.java:9`）。 |
| `status=0`（STATUS_PENDING）什么时候出现？ | 永远不会。常量定义了（`TaskCompensation.java:27`），但源码里**没有任何一处写入 0**；`markRetrying` 首次插入直接是 `STATUS_RETRYING=2`（`TaskCompensationRecorder.java:37-43`）。SQL 的 `DEFAULT '0'`（`hmdp.sql:293`）只是兜底。用 `status=0` 捞待办会一条都捞不到，**别在面试里说"待处理状态会被写进去"**。 |
| 为什么 `markSuccess` 要加 `ne(status, SUCCESS)`，`markFailed` 却不加？ | `markSuccess` 加是因为它会被**每轮重复调用**（订单已落库的订单会被反复扫到，直到预扣记录被删），必须让"成功"成为不可覆盖的终态、保住首次闭环时间。`markFailed` 没加是源码现状（`:68-73` 只有两个 `eq`）：它每轮也会被重复调用并重写 `update_time`，只是值幂等、无害。被问到时**照实说"当前实现如此，代价是 update_time 会被刷新"**，不要编造"失败也做了终态保护"。 |
| 达到上限之后，系统是彻底不管这单了吗？ | 不是。它仍在每轮 `SCAN` 范围内（`seckill:reservation:*` 还在，TTL 7 天，`seckill.lua:50`），只是**不再自动重投**；每轮仍会命中上限分支再记一次 `markFailed` 并打一条 `log.error`（`RepostNode.java:53-58`）。真正的收敛只有三条路：订单最终落库（`RepairNode.markSuccess`）、订单被取消（`ReleaseNode.markSuccess`）、Redis 记录到期消失。**人工介入通道目前是"查库 + 手工处理"，未接入告警/工单**（源码中未确认存在通知逻辑）。 |
| 人工怎么拿到"需要处理"的列表？ | 表结构支持：`SELECT * FROM tb_task_compensation WHERE biz_type='ORDER_CREATE' AND status=3`（列名见 `hmdp.sql:290-300`，`status=3` 即 `STATUS_FAILED`，`TaskCompensation.java:32-33`）。但**仓库里没有提供查询接口**——`TaskCompensationMapper` 只有 `BaseMapper`（`TaskCompensationMapper.java:9`），全仓库没有对账本表的 `selectList` 调用，也没有管理端 Controller。这条探测方式属于**运维侧 SQL，不是代码里的功能**。 |
| `lastError` 为什么长期是空的？ | 因为只有"达到上限转人工"才写非空值（`RepostNode.java:55-56`），而重投成功传的是 `null`（`:82`）、闭环也置 `null`（`TaskCompensationRecorder.java:62-63`）。所以 **`status=2` 的行 `lastError` 基本都是 `null`，这是正常的**：它只表达"当前这轮没有错误"，不保留历史错误。要保留完整错误轨迹得另加日志/事件表（源码中未确认）。 |
| 这类"补偿账本"和 MQ 自带重试、死信队列什么关系？ | 解决的不是同一层的问题。MQ 重试/死信处理的是"**消息消费失败**"（消息维度，框架内闭环）；账本记录的是"**预扣 → 订单落库**这条跨 Redis/MySQL 的修复过程"（订单维度，业务语义更上层）：重投次数、是否结案、放弃原因、下次期望时间都在账本里。两者可以同时存在，也可以互相印证。 |
| 账本会不会成为新的性能瓶颈？ | 不会成为热路径瓶颈：写入只有三个来源，全部在**异常补偿分支**（`RepostNode.java:55`、`:81`，`RepairNode.java:32`，`ReleaseNode.java:53`），正常下单链路一次 DB 都不写；表按 `biz_type + biz_id` 唯一定位，读写都是主键/唯一键级别的单行操作。真正的成本是"终态失败记录每轮被重复 `update`"（第 5、6 节），量级等于失败记录数 × 每分钟 1 次，可控。 |
| 怎么证明"有界重试"没有把该救的单提前放弃？ | 参数上：`stale-seconds` 默认 30 秒先给正常消费留时间（`RepostNode.java:38-40`），两次重投至少间隔 `retry-interval-seconds` 默认 60 秒（`:43-44`、`:92-99`），上限 `max-retry-count` 默认 5（`:46-48`）→ 最坏情况下累计给了一条订单约 **30 + 5×60 = 330 秒**的自愈窗口；超时才转人工并留下 `lastError` 与 `FAILED_MANUAL` 日志（`:55-59`）。**注意：源码中没有退避、没有抖动、也没有"上限动态调整"**，这些只能作为优化建议讲，不能说成已实现。 |

---

*本节全部结论来自以下真实源码：`TaskCompensation.java`、`TaskCompensationRecorder.java`、`TaskCompensationMapper.java`、`RepostNode.java`、`ReleaseNode.java`、`RepairNode.java`、`OrderLookupNode.java`、`ReservationRootNode.java`、`ReservationContext.java`、`ReservationRequest.java`、`RepairOutcome.java`、`ReconcileEndNode.java`、`SeckillReconciliationTask.java`、`FlowEngine.java`、`AbstractFlowNode.java`、`application.yaml`、`hmdp.sql`、`docs/sql/task_compensation.sql`、`src/main/resources/seckill.lua`、`RedisConstants.java`。文中标注"（源码中未确认）"的位置，均为本次只读检索未找到对应实现，未作推测。*


## 四、缓存可靠性治理

> 简历第 4 条拆成两问：**4.1 缓存可靠性**（逻辑过期 + 互斥锁解决击穿、缓存空值解决穿透）；**4.2 两级缓存**（Caffeine 本地 + Redis 远端 + MySQL 回源，降低热点查询压力）。一个保「不出事」，一个保「扛得住」。

### 4.1 缓存可靠性治理：逻辑过期 + 互斥锁 + 缓存空值

*对应简历第 4 条 · 缓存可靠性部分（原"第三问"）*

> **简历原文**：使用 Redis 逻辑过期解决热点店铺缓存击穿，并通过缓存空值降低缓存穿透风险。

#### 面试话术（四段式：背景 → 剖析 → 构思 → 复盘，可直接背）

**① 背景阐述**：店铺详情页是典型的读多写少接口，访问频次很高，基本都要靠缓存来抗。但缓存本身也有几个经典问题：缓存穿透、缓存击穿和缓存雪崩，这个优化点主要解决前两个。

**② 问题剖析**：先把两个问题说清楚，再讲方案：

- 缓存击穿：某个热点 Key 到期的瞬间，大量请求同时发现缓存失效，于是同时查数据库、同时回写缓存，形成"缓存重建风暴"，数据库可能瞬间被打满。它的特点是发生在"热点数据过期"的一瞬间；
- 缓存穿透：请求一条根本不存在的数据，比如一个不存在的店铺 ID，缓存永远不命中，每次请求都直接打到数据库。如果是恶意攻击，持续换不存在的 ID 请求，数据库压力会非常大；
- 针对击穿，常见做法有互斥锁和逻辑过期两种：互斥锁只让一个线程去重建、其他线程等待重试，一致性好，但其他请求要等、有锁竞争；逻辑过期永远返回数据、过期后只用一个线程异步重建，可用性最好，代价是可能短暂返回旧数据；
- 针对穿透，常见做法有布隆过滤器和缓存空值两种：布隆过滤器能提前挡掉，但有误判、删除麻烦，还要维护全量数据；缓存空值简单直接，查不到就写一个短过期的空值，代价是短暂的额外内存和弱一致性。

**③ 方案构思**：结合项目特点，我最终选的是"逻辑过期 + 空值缓存"：

- 逻辑过期怎么实现：缓存 Key 不设置短 TTL 去删除，而是把真正的过期时间写到 value 里，value 是一个包装对象，包含数据和 expireTime。请求进来先读缓存：没命中说明是冷数据，直接查库回填；命中就判断 expireTime，没过期直接返回；过期了也不是删除重建，而是先抢互斥锁（SETNX，带过期时间防死锁），抢到的线程把重建任务提交到线程池异步执行，其他线程直接返回旧值，谁都不用等；
- 空值缓存怎么实现：查库结果为空，就往 Redis 写一个空字符串，TTL 设 2 秒。之后的请求在缓存层命中空值，直接返回"店铺不存在"，不会穿透到数据库；2 秒很短，万一数据后来补上了也能快速恢复；
- 额外兜底：逻辑过期的 Key 我加了 24 小时物理 TTL，防止长期没人访问的 Key 永不过期、无界占用内存；更新数据时走"先更新数据库、再删除缓存"，把不一致窗口交给 TTL 收敛。

**④ 复盘总结**：这块记住两组对应关系就行——击穿对应"逻辑过期 + 互斥锁重建"，用一点旧数据换高可用；穿透对应"空值缓存"，用很小的成本挡住无效请求。再补上物理 TTL 和正确的更新策略，缓存的可用性和一致性就都能兜住了。

#### 1. 这条在说什么

两个不同的问题：

- **缓存击穿**：某个热点 Key（如店铺详情）过期瞬间，大量并发请求同时发现「缓存没了」，一起冲去查 MySQL。解法是逻辑过期：Key 不物理删除，过期后先返回旧值，只让一个线程后台重建。
- **缓存穿透**：请求的 ID 在缓存和数据库里都不存在，每次都穿到数据库。解法是缓存空值：数据库查不到就写一个短 TTL 的空值进 Redis，下次同样的请求直接命中空值返回。

**这条优化的意义**

- **保护数据库**：缓存层存在的意义就是替数据库挡读流量；击穿和穿透会让这层「漏」，流量绕过缓存直接打 MySQL；
- **没有它会怎样**：
  - 击穿：热点店铺 Key 过期的瞬间，成百上千并发同时回源，数据库瞬间被打爆，甚至连锁拖垮其他接口；
  - 穿透：恶意的随机 ID 每次都不命中缓存也不命中数据库，长期空转打库；
- **本质**：缓存不仅要「能命中」，还要在「命中不了」的时候可控——逻辑过期让过期瞬间仍返回旧值、只放一个线程重建；空值缓存让「不存在」也变成可命中结果。

#### 2. 逻辑过期完整执行步骤（示例：`cache:shop:1`）

```mermaid
flowchart TD
    A["GET cache:shop:{id}"] --> B{"Key 存在?"}
    B -->|"否"| C{"缓存值是空字符串?"}
    C -->|"是"| C1["返回空（负缓存命中，不查库）"]
    C -->|"否"| C2["查库回填：没有则写空值 2 秒<br/>有则按逻辑过期格式写入并返回"]
    B -->|"是"| D{"expireTime 过期?"}
    D -->|"否"| E["返回缓存值"]
    D -->|"是"| F{"抢锁成功?"}
    F -->|"是"| G["线程池异步重建"]
    F -->|"否"| H["不等锁"]
    G --> I["立即返回旧值"]
    H --> I
```

1. 请求进来，`GET cache:shop:1`；

2. Key **不存在** → 查一次数据库：查不到就写空值（`""`，TTL 2 秒）并返回“店铺不存在”（防穿透）；查到就按逻辑过期格式写入缓存并返回；如果 Key 存在但值是空字符串，说明命中的是负缓存，直接返回 null、不查库；

3. Key 存在 → 反序列化出 `RedisData`，里面有两部分：`data`（店铺数据）和 `expireTime`（逻辑过期时间）；

4. `expireTime` 还没到 → 直接返回缓存值，结束；

5. `expireTime` 已到 → 尝试抢锁 `lock:shop:1`：
   
   - 抢到锁：提交线程池异步查 MySQL、重建缓存；
   
   - 没抢到锁：不等、不重试；

6. 无论有没有抢到锁，**当前请求立即返回旧值**。

和「物理过期 + 互斥锁」的区别：物理过期一旦 Key 被删，晚到的请求没有旧值可返回，只能等锁或回源；逻辑过期永远有旧值兜底，过期瞬间的响应时间稳定。

#### 3. 这里的互斥锁是什么锁

不是 Redisson，是手写的 SETNX 锁（`CacheClient.java:191-203`）：

```java
private boolean tryLock(String key) {
    Boolean flag = stringRedisTemplate.opsForValue()
            .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
            // 等价命令：SET lock:shop:1 1 NX EX 10
            // NX：Key 不存在才能设置成功（成功 = 抢到锁）；EX 10：10 秒后自动过期
    return BooleanUtil.isTrue(flag);   // true = 抢到；false / null = 没抢到
}

private void unLock(String key) {
    stringRedisTemplate.delete(key);   // 等价命令：DEL lock:shop:1
}
```

SET   lock:shop:1   1   NX   EX 10
 │        │        │    │     │
 │        │        │    │     └─ EX 10 = 10 秒后自动过期
 │        │        │    └─────── NX = 只有当 key「不存在」时才设置成功
 │        │        └──────────── value = 1（随便写个占位值）
 │        └───────────────────── key = 锁的名字（lock:shop: + 店铺 ID）
 └────────────────────────────── Redis 命令：设置一个 key-value


- 用在哪：只用于「过期后谁来重建缓存」，`LOCK_SHOP_TTL = 10` 秒；
- 为什么不用 Redisson：抢不到锁就直接返回旧值，不等待、不重试，任务很短（查一次库 + 写缓存），不需要看门狗续期和可重入；
- 项目里 Redisson 用在另一处：消费端订单防重锁 `lock:order:{userId}`（2.1 的 4.4）。

#### 4. 缓存空值完整执行步骤（`CacheClient#queryWithPassThrough`）

1. `GET cache:shop:{id}`；

2. 命中正常值 → 返回；

3. 命中空字符串 `""` → 返回 null（避免再次查库）；

4. 都没命中 → 查数据库：
   
   - 数据库有 → 写入缓存，正常 TTL；
   
   - 数据库没有 → 写入空值 `""`，TTL 2 秒（`CACHE_NULL_TTL`）；

5. 下次请求同一个不存在的 ID，第 3 步直接命中空值，不再访问数据库。

**当前状态说明**：空值缓存现在有三个落点（前两个已生效）：

- **店铺详情（链路二）**：已接在 `CacheClient#queryWithLogicalExpire` 的“Key 不存在”分支里——查库为空 → 写 `""`（`CACHE_NULL_TTL = 2 秒`）→ 下次同 ID 命中空值直接返回，不再打库；
- **秒杀券详情（链路三）**：`VoucherServiceImpl#querySeckillVoucherDetail` 查库为空时写 `""`（2 秒），读命中空值直接返回“优惠券不存在”；
- **通用方法未接主链路**：`CacheClient#queryWithPassThrough`（`CacheClient.java`）仍然没有生效中的调用，它的逻辑与上面两处相同（第 4 节讲的四步就是它）。

#### 5. 核心代码

**5.1 逻辑过期**（`CacheClient.java:120-178`）

```java
public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                        Function<ID, R> dbFallback, Long time, TimeUnit unit) {
    String key = keyPrefix + id;                          // 例：cache:shop:1
    String json = stringRedisTemplate.opsForValue().get(key);   // 读 Redis
    // 命中空值缓存：数据库确认过这个 ID 不存在，直接返回（防穿透）
    if (json != null && json.isEmpty()) {
        return null;
    }
    // Key 完全不存在：缓存未预热（或负缓存已过期），查一次库并回填
    if (json == null) {
        R dbResult = dbFallback.apply(id);
        if (dbResult == null) {
            this.set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);   // 负缓存，TTL 2 秒
            return null;
        }
        this.setWithLogicalExpire(key, dbResult, time, unit);      // 查到：写入并返回
        return dbResult;
    }
    // 命中，反序列化：JSON 里是 { data: 店铺数据, expireTime: 逻辑过期时间 }
    RedisData redisData = JSONUtil.toBean(json, RedisData.class);
    JSONObject jsonObject = (JSONObject) redisData.getData();
    R r = BeanUtil.toBean(jsonObject, type);              // 还原成 Shop 对象
    LocalDateTime expireTime = redisData.getExpireTime();
    if (expireTime.isAfter(LocalDateTime.now())) {
        return r;                        // 未过期：直接返回
    }
    // 已过期：抢锁，只让一个线程去重建
    String lockKey = LOCK_SHOP_KEY + id;                  // lock:shop:1
    boolean flag = tryLock(lockKey);
    if (flag) {                                           // 抢到锁
        CACHE_REBUILD_EXECUTOR.submit(() -> {             // 丢给线程池，当前线程不等待
            try {
                R newR = dbFallback.apply(id);            // 回调查数据库（this::getById）
                this.setWithLogicalExpire(key, newR, time, unit);   // 重建缓存
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);                          // 重建完释放锁
            }
        });
    }
    return r;                            // 抢没抢到锁都先返回旧值
}
```

**5.2 写入逻辑过期数据**（`CacheClient.java:58-68`）

```java
public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
    RedisData redisData = new RedisData();                 // 包装对象：data + expireTime
    redisData.setData(value);                              // 真正的店铺数据
    redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
    // 逻辑过期时间 = 当前时间 + 业务时长（如 30 分钟）
    // 逻辑过期判断仍靠 value 里的 expireTime；这里再给 Key 一个物理 TTL 兜底，
    // 防止长期无人访问的 Key 永不过期、无界占用内存（物理过期后走"未命中回填"路径）
    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData),
            LOGICAL_EXPIRE_FALLBACK_TTL, TimeUnit.HOURS);   // 24 小时兜底
}
```

**5.3 空值缓存**（`CacheClient.java:97-107`）

```java
if ("".equals(json)) {
    return null;                                        // 命中的是空值缓存：直接返回，不查库
}
R r = dbFallback.apply(id);                             // 缓存未命中，查数据库
if (r == null) {
    this.set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
    // 数据库也没有 → 写一个空字符串，TTL 2 秒；下次同样的 ID 在 2 秒内不再查库
    return null;
}
```

**5.4 启用位置**（`ShopServiceImpl.java:47-60`）

```java
@Override
public Result queryById(Long id) {
    Shop shop = cacheClient.queryWithLogicalExpire(
            CACHE_SHOP_KEY,        // Key 前缀：cache:shop:
            id,
            Shop.class,
            this::getById,         // 回源回调：缓存未命中时用它查数据库（组件不直接依赖 Mapper）
            CACHE_SHOP_TTL,        // 逻辑过期时长：30 分钟
            TimeUnit.MINUTES);
    if (shop == null) {
        return Result.fail("店铺不存在");
    }
    return Result.ok(shop);
}
```

#### 6. 为什么逻辑过期而不是互斥锁方案

| 方案 | 决定性因素 | 判定 |
| --- | --- | --- |
| 物理 TTL 裸回源 | 过期瞬间并发全打 MySQL | 淘汰 |
| 物理 TTL + 互斥锁 | 未抢到锁的请求要等待或重试，响应时间抖动 | 一致性敏感场景可用 |
| **逻辑过期 + 互斥重建** | 过期后先返回旧值，单线程后台重建，热点请求不等待 | 店铺详情采用 |

**如果条件变了**：余额、库存等强一致数据不能用逻辑过期（允许旧值）；需要读最新值的场景用物理 TTL + 互斥锁。

先列清楚「热点 Key 失效」这个问题的硬需求，再对比：

| 需求 | 说明 |
| --- | --- |
| 失效瞬间不能打挂数据库 | 热门店铺详情被大量并发访问，Key 一失效，所有请求同时回源 |
| 读请求不能被阻塞 | 详情页是 C 端读接口，用户等不起「别人查完我再返回」 |
| 允许短暂旧数据 | 店铺名称、营业时间这类信息晚几百毫秒更新，用户无感 |
| 回源只能有一个 | 重建要查库，不能让并发请求一起查库 |
| 内存不能失控 | 逻辑过期的 Key 物理上不过期，必须有兜底 |

**结论：不是互斥锁方案「不行」，是它在这个场景代价更高：**

- **物理 TTL 裸回源**：最省事，但 Key 一到期所有并发请求同时打 MySQL——这就是「缓存击穿」本身，直接淘汰；
- **物理 TTL + 互斥锁**：未命中 → 加锁 → 查库 → 回填 → 解锁，没抢到锁的请求**等待后重试**。正确性强（读到的一定是新值），代价是**响应时间抖动**：热点 Key 每秒可能被访问上万次，而锁的持有时间等于一次数据库查询的时间，等待队列会明显拉长 P99；
- **逻辑过期 + 互斥重建**：Value 里自带 `expireTime`，物理 Key 不设短 TTL。发现逻辑过期时**先把旧值返回给用户**，同时只让一个实例去后台重建——用户永远不用为「重建」买单，代价是短时间内读到旧数据。

**本项目实际是三段组合（重点展开）**

代码不是简单的「二选一」，`CacheClient.queryWithLogicalExpire` 按「有没有旧值可返」把两种思路拆成了三条路径（`CacheClient.java:155-223`）：

1. **有旧值、且逻辑过期 → 返回旧值 + 异步重建**（逻辑过期方案）：抢到重建令牌的请求把重建任务提交到线程池（`CACHE_REBUILD_EXECUTOR`，10 个线程，`CacheClient.java:274`），**其余请求立刻拿到旧数据返回**（`:209-222`）。这就是「读请求永不阻塞」的来源；
2. **Key 完全不存在（没有旧值可返）→ 抢令牌 + 有界等待**（互斥锁方案）：这时逻辑过期救不了你，因为没东西可返。于是抢重建令牌自己回源（`:178-186`）；没抢到的做**有界等待 3 次 × 20ms**（`REBUILD_WAIT_RETRY = 3`、`REBUILD_WAIT_INTERVAL_MS = 20`，`:38-39`、`:188-194`），等到了直接用；等不到就自己回源（`:195-197`）——用「最多 60 毫秒」换掉「无限等待」，同时不会形成雪崩；
3. **Redis 读异常 → 降级直查数据库**（`:166-171`）：缓存层故障时不让整个接口挂掉，代价是这一刻数据库要扛全量流量。

**为什么重建也要一把锁？** 因为重建就是查库。「异步」不等于「谁都可以重建」——10 个请求同时发现过期、10 个都去查库，数据库照样被打。所以重建前先抢 `lock:rebuild:{key}`（`REBUILD_LOCK_PREFIX`，`:36`，从缓存 Key 派生，天然做到不同缓存不互抢），**只有抢到的那一个去查库**；解锁用的是内联 Lua 的「比对持有者 + 删除」：

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end
```

如果用「先 `GET` 再 `DEL`」两条命令，两步之间令牌可能刚好过期并被别人抢到，就会**误删别人的令牌**——用 Lua 才能真正做到「只删自己的」（`CacheClient.java:41-48`）。

两边能力对照：

| 维度 | **逻辑过期 + 互斥重建（选）** | 物理 TTL + 互斥锁 | 物理 TTL 裸回源 |
| --- | --- | --- | --- |
| 热点 Key 失效时的读延迟 | 不变（直接返回旧值） | 抖动（等锁 + 等一次查库） | 抖动（所有请求一起查库） |
| 数据新鲜度 | 最终一致（重建完成前是旧值） | 强一致（读到的一定是回源结果） | 强一致 |
| 数据库压力 | 单实例单次回源 | 单实例单次回源，但请求排队 | 并发回源，可能打挂 |
| Key 不存在时（无旧值可返） | 抢令牌 + 有界等待 3×20ms，超时自行回源 | 等锁 | 直接穿透到库 |
| 内存 | 需要物理 TTL / 淘汰策略兜底 | 靠 TTL 自然回收 | 同左 |
| 适用数据 | 店铺详情、商品描述这类「晚一点没关系」的读多写少数据 | 余额、库存、权限这类强一致数据 | 无 |

一句话：**热点读接口的用户体验优先于「读到的一定是最新值」**——所以主路径选「返回旧值 + 后台重建」，但把「没有旧值可返」这个漏洞用有界等待的互斥锁补上。这就是本项目把逻辑过期和互斥锁**组合**使用、而不是二选一的原因。

**追问：返回旧数据，业务能接受吗？** 看数据语义。店铺名称、营业时间、优惠券描述这类晚几百毫秒无感；但**库存、余额、订单状态绝不能用**——所以秒杀券详情页的库存是实时读 Redis 库存 Key 补进 DTO 的（`VoucherServiceImpl.fillLiveStock`，`VoucherServiceImpl.java:113`、`:193`），没有走逻辑过期缓存（详见 4.2 第 3 节）。

**追问：逻辑过期 Key 物理不过期，内存会不会爆？** 不会因为「请求多」而增长：每个 `cache:shop:{id}` 只有一个 Key，重建是覆盖写，Key 数量上限 = 被访问过的店铺数（不是访问次数）。真正的兜底是写入时额外 `EXPIRE 24 小时`（`LOGICAL_EXPIRE_FALLBACK_TTL = 24L`，`RedisConstants.java:14`，在 `CacheClient.java:84` 使用）：24 小时内靠 `expireTime` 逻辑过期，超过 24 小时无人访问就由 Redis 物理删除，下次请求走「未命中回填」。生产上还应配 `maxmemory` + `allkeys-lru`，本机当前是 `maxmemory = 0` / `noeviction`。

**追问：怎么测「逻辑过期」这条路径？** 不能删 Key——删 Key 走的是上面第 2 条「未命中回填」。要测逻辑过期，得把 Value 里的 `expireTime` 改到过去（直接改 Redis 里那段 JSON），这样才会触发「返回旧值 + 异步重建」。

#### 7. 边界（面试追问）

| 追问               | 回答                                                                                                              |
| ---------------- | --------------------------------------------------------------------------------------------------------------- |
| 击穿 / 穿透 / 雪崩的区别？ | 击穿是单个热点 Key 失效；穿透是查不存在的数据；雪崩是大量 Key 同时失效                                                                        |
| 逻辑过期还需要预热吗？      | 不需要：Key 不存在会自动查库回填（查不到写空值负缓存）。但测试逻辑过期时要改 Value 里的 `expireTime`，不能删 Key——删 Key 走的是“未命中回填”，测不到“过期返回旧值 + 异步重建”这条路径 |
| 空值和逻辑过期在同一路径生效吗？ | 是，店铺详情上两者已组合：Key 不存在 / 值为空 → 空值负缓存防穿透；Key 存在且 `expireTime` 过期 → 逻辑过期防击穿。秒杀券详情（链路三）也接了空值负缓存                      |
| 缓存更新怎么保证一致？      | `ShopServiceImpl#update` 先更新数据库、再删除缓存（`ShopServiceImpl.java:199-201`）；删除失败的窗口由 TTL 收敛                           |

**内存兜底：逻辑过期 Key“永不过期”会不会撑爆内存？**

不会因“请求多”而增长——每个 `cache:shop:{id}` 只对应一个 Key，重建就是覆盖，Key 数量上限 = 被访问过的店铺数（不是访问次数）。但“Key 无 TTL + Redis 无 maxmemory + noeviction”三者叠加确实危险，用四层兜底：

| 手段                 | 做法                                                                                                                                         | 代价                               |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------- |
| **物理 TTL 兜底（已实现）** | `setWithLogicalExpire` 写入时额外 `EXPIRE 24 小时`（`LOGICAL_EXPIRE_FALLBACK_TTL`）：24 小时内靠 `expireTime` 逻辑过期；超过 24 小时无人访问由 Redis 物理删除，下次请求走“未命中回填” | 物理过期瞬间没有旧值可返回（有回填 + 负缓存，不会打挂数据库） |
| LRU/LFU 淘汰（生产建议）   | `maxmemory 1gb` + `maxmemory-policy allkeys-lru`（或 `allkeys-lfu`）                                                                          | 冷 Key 被淘汰后同样走未命中回填               |
| 主动清理               | 店铺下架/删除时同步删 `cache:shop:{id}`（当前 `ShopServiceImpl#update` 会删，删除店铺没有实现）                                                                     | 每个写操作都要记得清缓存                     |
| 监控告警               | `used_memory`、`DBSIZE`、大 Key（`redis-cli --bigkeys`）阈值告警                                                                                    | 只发现，不解决                          |

本机 Redis 现状（提醒）：`maxmemory = 0`、`maxmemory-policy = noeviction`——本地演示没问题，生产必须配淘汰策略。

---

### 4.2 两级缓存治理：Caffeine + Redis + MySQL 回源

*对应简历第 4 条 · 两级缓存部分（原"第七问"）*

> **简历原文**：使用 Caffeine 为店铺优惠券列表构建本地一级缓存，结合 Redis 二级缓存与 MySQL 回源，减少热点查询对 Redis 和数据库的访问压力

#### 面试话术（四段式：背景 → 剖析 → 构思 → 复盘，可直接背）

**① 背景阐述**：秒杀优惠券是商家在后台配置的，用户端有两个典型入口——店铺的优惠券列表和秒杀券详情页。它们查的是同一类数据：更新频率极低（标题、价格、规则、时间这些字段发布后几乎不改）、访问频率极高（活动页一打开就会被反复刷新）。这种数据最适合做缓存优化。

**② 问题剖析**：这条链路之前已经接了 Redis 缓存，但还有两个问题：

- **Redis 单实例性能有上限**——热点 Key 的流量集中打在 Redis 上，QPS 一高，Redis 本身就会成为系统瓶颈；
- 就算 Redis 扛得住，每次请求还是有一趟"网络往返 + 序列化"的开销，对极热数据来说是纯浪费。

所以优化方向很明确：把"最热的那部分访问"再往前挪一层，挪到应用进程自己的内存里。

**③ 方案构思**：我用 Caffeine（进程内缓存）+ Redis 搭了二级缓存，请求流程是"三级逐级回源、逐级回填"：

1. 先查本地缓存 Caffeine（5 秒过期、上限 1000）——命中直接返回，完全不走网络；
2. 没命中再查 Redis（30 秒）——命中就把数据回填本地缓存，再返回；
3. Redis 也没命中才查 MySQL——查到后先回填 Redis、再回填本地缓存，最后返回。

最热的请求大部分被挡在各个应用实例的内存里，Redis 的压力就被分散掉了。两条链路各用一个独立的 Caffeine 实例：券列表以 shopId 为 key，秒杀详情以 voucherId 为 key，互不干扰。

本地缓存绕不开的一个问题是：服务集群部署时，每个实例内存里都有一份拷贝，数据更新要让所有节点同时失效，就得做广播（发消息、各节点监听）——实现复杂度明显上升。所以我用了更简单的方案：**给本地缓存设一个很短的 TTL（5 秒），不主动同步，只靠 TTL 自然过期、由下一次访问回源刷新**；代价是数据最多旧 5 秒，对券信息这种几乎不变的数据完全可以接受。

配套细节：

- 防穿透：秒杀详情查库为空时，往 Redis 写一个 2 秒的空值（负缓存），不存在的券 ID 不再打库；
- 库存特殊处理：不进任何缓存，每次实时读 Redis 的 `seckill:stock:{id}`——高频变化的数据不能容忍缓存延迟；
- 写路径：新增秒杀券时同时失效本地券列表缓存（invalidate）+ 删除 Redis 券列表缓存，本实例立即生效，其他实例靠 5 秒 TTL 收敛。

**④ 复盘总结**：一句话总结——当热 Key 让 Redis 压力过高、而数据本身又极少更新时，用"Caffeine 本地缓存 + Redis"搭二级缓存，把热流量分散到各应用实例的内存里；集群一致性用"短 TTL 懒刷新"替代"广播同步"，简单且够用。再把防穿透、库存实时读、写路径失效这几个细节补上，整套就完整了。

#### 1. 这条在说什么

店铺优惠券列表、秒杀券详情是典型的「读远多于写」热点数据：活动页一被打开，同一店铺 / 同一张券会被反复查询。Redis 虽然快，但每次请求仍有 **TCP 往返 + JSON 序列化 + 连接池占用**的成本，所以再往应用进程里加一层更快的 L1：

```text
L1 Caffeine（进程内，5 秒，纳秒级，无网络）
  → L2 Redis（跨实例共享，30 秒，一次网络往返）
    → L3 MySQL（事实来源，最慢）
```

一条请求先查 L1，命中直接返回；未命中查 L2，命中后回填 L1；再未命中才回 MySQL，并依次回填 L2、L1。核心收益：**L1 命中完全不碰 Redis，L2 命中完全不碰 MySQL**，把极热请求挡在离用户最近的地方。

```text
示意图（假设 L1 命中率 95%）：
没有 L1：10000 QPS → 10000 次 Redis GET
有  L1：10000 QPS → 约 500 次 Redis GET，其余全在 JVM 内存里
```

**这条优化的意义**

- **降延迟**：L1 命中是进程内内存访问（纳秒级），相比 Redis 省掉网络往返 + 序列化；
- **降压力**：极热读被截在本机，Redis 只承接 L1 未命中的部分，数据库只承接两层都未命中的部分——越靠前的层挡住越多的量；
- **没有它会怎样**：
  - 只有 Redis：活动页热点读按 QPS 全量打 Redis，网络带宽、连接池、单分片都会成为瓶颈；
  - 只有 Caffeine：多实例互不共享、重启即失效、容量有限，数据一变各实例各自为政；
- **本质**：用「分层 + 短 TTL」换吞吐和延迟，接受「多实例短时间内可能读到旧值」（5 秒收敛），所以只放「变了也没关系」的字段（库存被专门排除）。

#### 2. 完整执行步骤（示例：shopId = 1）

```mermaid
flowchart TD
    A["GET /voucher/list/1"] --> B{"L1 Caffeine<br/>getIfPresent(1)"}
    B -->|"命中"| C["直接返回（0 网络）"]
    B -->|"未命中"| D{"L2 Redis GET<br/>cache:voucher:list:1"}
    D -->|"命中"| E["反序列化<br/>回填 L1（5s）<br/>返回"]
    D -->|"未命中"| F["L3 MySQL<br/>queryVoucherOfShop(1)"]
    F --> G["写 Redis（TTL 30s）<br/>写 Caffeine（TTL 5s）"]
    G --> H["返回"]
```

先把这条流程里的几个词变成大白话：

| 词            | 大白话                                                                       |
| ------------ | ------------------------------------------------------------------------- |
| L1 / L2 / L3 | 第 1/2/3 层缓存：L1 = Caffeine（**本应用的内存**）、L2 = Redis（独立服务）、L3 = MySQL（最底层数据库） |
| 命中 / 未命中     | 在缓存里**找到了 / 没找到**（没找到也包括「已过期」）                                            |
| 回源           | 缓存里没有，只能去最底层数据库查真数据                                                       |
| 回填           | 查到数据后写回缓存，让下次请求能直接命中                                                      |
| 序列化 / 反序列化   | Java 对象 → 字符串（存 Redis 用） / 字符串 → Java 对象（从 Redis 读出来后还原）                  |
| 0 网络         | 数据就在本机内存，不需要和 Redis、MySQL 通信                                              |

然后跟着一次真实请求走一遍（shopId=1；两层缓存都是空的，这张券只在 MySQL 里）：

1. **查 L1：翻本机内存**。`localVoucherCache.getIfPresent(1)` —— 拿 shopId=1 当钥匙，去内存里那张表翻有没有 1 号店的券列表。结果：**没翻到**，得到 `local = null`；

2. **L1 命中判断**。代码 `if (local != null) return ...` —— 如果第 1 步翻到了，这里就直接把券列表返回、方法结束，Redis 和 MySQL 一次都不会被访问。本次没命中，继续；

3. **查 L2：让 Redis 帮忙找**。`GET cache:voucher:list:1` —— Redis 里存的是**字符串**（不是 Java 对象），key 是 `cache:voucher:list:{shopId}`。结果：**没有这个 key**，`cached = null`，继续；

4. **L2 命中判断**。如果 Redis 里有：先把 JSON 字符串变回 `List<Voucher>`（**反序列化**），再把它塞回本机内存（**回填 L1**，这样下一个请求在第 1 步就能命中），然后返回。本次没命中，继续；

5. **查 L3：回源 MySQL**。`getBaseMapper().queryVoucherOfShop(1)` 执行下面的 SQL，拿到真数据 `vouchers = [券 V1]`：
   
   ```sql
   SELECT v.id, v.shop_id, v.title, v.sub_title, v.rules, v.pay_value,
          v.actual_value, v.type, sv.stock, sv.begin_time, sv.end_time
   FROM tb_voucher v
   LEFT JOIN tb_seckill_voucher sv ON v.id = sv.voucher_id
   WHERE v.shop_id = 1 AND v.status = 1
   ```

6. **把结果写回两层缓存，然后返回**（对应 4.2 补充里的 ⑩⑪）：
   
   ```java
   // 写 L2：Java 对象 → JSON 字符串，SETEX 带 30 秒过期
   stringRedisTemplate.opsForValue().set(
           "cache:voucher:list:1",
           JSONUtil.toJsonStr(vouchers),            // '[{"id":10,"title":"100元代金券",...}]'
           RedisConstants.CACHE_VOUCHER_LIST_TTL,   // 30
           TimeUnit.SECONDS);                       // Redis 实际执行：SETEX cache:voucher:list:1 30 '<json>'
   
   // 写 L1：同一个 Java 对象直接放进进程内存（不序列化），5 秒后自动过期
   localVoucherCache.put(1, vouchers);
   
   return Result.ok(vouchers);                      // Spring 序列化为 JSON 响应给前端
   ```
   
   - 写 L2 前要 `JSONUtil.toJsonStr`：Redis 只能存字符串/字节，Java 对象不能直接塞；写 L1 不用转：Caffeine 存的就是对象本身；
   
   - 这次请求到这里结束，但它把数据「热」在了两层缓存里：0~5 秒内再来的请求在第 1 步命中 L1，5~30 秒的请求在第 3 步命中 L2（下一个表给对照）。

第一次请求前后各层状态一览：

| 层                               | 执行前        | 执行后                    |
| ------------------------------- | ---------- | ---------------------- |
| L1 Caffeine                     | 无 shopId=1 | `List<Voucher>`，5 秒后过期 |
| L2 Redis `cache:voucher:list:1` | 不存在        | JSON 列表，TTL 30 秒       |
| L3 MySQL                        | 事实来源       | 不变                     |

上面那张表是怎么一步步来的（假设 1 号店有一张券，三层全未命中）：

```text
执行前
  L1 Caffeine : {}                                  （空）
  L2 Redis    : 没有 cache:voucher:list:1
  MySQL       : tb_voucher 里有 shop_id=1 的券
        │
        │  第 1 步  getIfPresent(1)             → null（L1 未命中）
        │  第 3 步  GET cache:voucher:list:1     → nil（L2 未命中）
        │  第 5 步  SELECT ...                   → [券 V1]（回源成功）
        ▼
  第 6 步  写 L2：SETEX cache:voucher:list:1 30 '<json>'
           写 L1：put(1, [券 V1])
        │
        ▼
执行后（这次请求已把结果返回给前端）
  L1 Caffeine : {1 → [券 V1]}                       5 秒内有效
  L2 Redis    : cache:voucher:list:1 = JSON 列表     TTL 30 秒
  MySQL       : 不变
```

之后再来请求的三种情况：

| 距第一次请求   | L1  | L2  | 走哪条路                   |
| -------- | --- | --- | ---------------------- |
| 0 ~ 5 秒  | 有   | 有   | 第 2 步：L1 命中，0 网络       |
| 5 ~ 30 秒 | 已过期 | 有   | 第 4 步：L2 命中，回填 L1 后返回  |
| 30 秒后    | 已过期 | 已过期 | 第 6 步：重新回源 MySQL 并回填两层 |

**失效路径**（新增秒杀券 `addSeckillVoucher`，`VoucherServiceImpl.java:143-157`）：

1. 写 MySQL：`save(voucher)`（tb_voucher）+ `seckillVoucherService.save(...)`（tb_seckill_voucher）；
2. 写 Redis：`SET seckill:stock:{id}` 预热秒杀库存（业务数据，不是缓存）；
3. `localVoucherCache.invalidate(shopId)` 删本实例 L1；
4. `DEL cache:voucher:list:{shopId}` 删 L2；
5. 下一次读取按第 1 步重新回源。

#### 3. 库存为什么不进缓存（详情页的关键设计）

秒杀券详情页（`querySeckillVoucherDetail`）同样是 Caffeine 5s → Redis 30s → MySQL，但 DTO 里的字段是分级的：

| 字段                       | 是否进缓存   | 原因                        |
| ------------------------ | ------- | ------------------------- |
| 标题 / 副标题 / 规则 / 开始结束时间 等 | 进（两级都进） | 几乎不变，允许 5 秒旧值             |
| `stock`                  | **不进**  | 高频变化，秒杀本身靠库存判断，5 秒旧值会误导用户 |

每次命中缓存后都实时补库存：

```java
/** 库存不进缓存：每次从 Redis 资格库存实时读取，读不到就保留 MySQL 快照。 */
private void fillLiveStock(SeckillVoucherDetailDTO dto) {
    String liveStock = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + dto.getId());
    if (liveStock != null) {
        dto.setStock(Integer.valueOf(liveStock));
    }
}
```

即：**缓存只放「变了也没关系」的字段，敏感字段每次实时读**——这是两级缓存设计里比「加一层」更重要的原则。

#### 4. 核心代码

> 下面代码里的英文单词（`Voucher`、`Cache`、`getIfPresent`…）看不懂时，见文末「附录 A：代码英文 / 术语速查」。

**4.1 两个 Caffeine 缓存实例**（`VoucherServiceImpl.java:42-52`）

```java
/** 本地热点券列表缓存：key = shopId(Long)，value = 该店铺的券列表 List<Voucher>。
 *  TTL 5 秒，降低单热点 Key 对 Redis 的访问压力。 
 用户点进店铺页，展示“这家店有哪些券”。
 */
private final Cache<Long, List<Voucher>> localVoucherCache = Caffeine.newBuilder()
        .maximumSize(1000)                      // 最多缓存 1000 个店铺的列表，防内存膨胀
        .expireAfterWrite(5, TimeUnit.SECONDS)  // 每个 key 写入/覆盖后 5 秒过期
        .build();

/** 秒杀券详情页本地缓存：key = voucherId(Long)，value = 详情对象 SeckillVoucherDetailDTO。
 *  TTL 5 秒，抗“秒杀详情”这个极热 Key。 
 用户点进某张秒杀券的详情页，展示“这张券的库存、开始时间、规则”。
 */
private final Cache<Long, SeckillVoucherDetailDTO> localSeckillDetailCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(5, TimeUnit.SECONDS)
        .build();
```

**4.1 补充（一）：两个缓存里到底存的是什么**

一句话：**Caffeine 就是一个「key → value」的进程内 Map**，两个缓存的区别只有 key 和 value 的类型：

| 缓存                        | key 是什么                | value 是什么                           | 对应接口                               |
| ------------------------- | ---------------------- | ----------------------------------- | ---------------------------------- |
| `localVoucherCache`       | `Long shopId`（店铺 ID）   | `List<Voucher>`（该店铺的券列表）            | 查店铺券列表 `queryVoucherOfShop`        |
| `localSeckillDetailCache` | `Long voucherId`（券 ID） | `SeckillVoucherDetailDTO`（一张券的详情对象） | 查秒杀券详情 `querySeckillVoucherDetail` |

放进内存后实际长这样（都是 Java 对象，没有序列化）：

```text
localVoucherCache
  key(shopId)   →  value(List<Voucher>)
  1L            →  [ Voucher{ id=10, shopId=1, title="100元代金券", subTitle="周一至周日均可使用",
                              rules="全场通用...", payValue=8000, actualValue=10000,
                              type=1, status=1, stock=95,
                              beginTime=2022-10-09T00:00, endTime=2022-10-10T20:00 },
                     Voucher{ id=11, shopId=1, title="80元代金券", ... } ]
  2L            →  [ Voucher{ id=20, shopId=2, ... } ]

localSeckillDetailCache
  key(voucherId) →  value(SeckillVoucherDetailDTO)
  10L            →  SeckillVoucherDetailDTO{ id=10, shopId=1, title="100元代金券",
                                              subTitle=..., rules=..., payValue=8000, actualValue=10000,
                                              type=1, status=1, stock=95,
                                              beginTime=2022-10-09T00:00, endTime=2022-10-10T20:00 }
```

两个 value 类型从哪来：

- `List<Voucher>`：就是 `VoucherMapper.xml` 那条 SQL（`tb_voucher LEFT JOIN tb_seckill_voucher`）的查询结果，一行券对应一个 `Voucher` 对象，多张券组成 List；
- `SeckillVoucherDetailDTO`：把 `Voucher` + `SeckillVoucher` 两个实体用 `BeanUtil.copyProperties` 拼出来的详情对象（`VoucherServiceImpl.java:118-120`），专门给详情页用。

`Voucher` 对象的字段（`Voucher.java`）：

| 字段                                                                                                                              | 类型                                      | 来源                                                                                  | 说明                |
| ------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------- | ----------------------------------------------------------------------------------- | ----------------- |
| `id` / `shopId` / `title` / `subTitle` / `rules` / `payValue` / `actualValue` / `type` / `status` / `createTime` / `updateTime` | Long / String / Integer / LocalDateTime | `tb_voucher` 表字段                                                                    | 券基本信息             |
| `stock` / `beginTime` / `endTime`                                                                                               | Integer / LocalDateTime                 | `tb_seckill_voucher` 表字段（`@TableField(exist = false)`：不属于 `tb_voucher` 列，由 JOIN 带出） | 秒杀信息；普通券这三列为 null |

**顺手弄清：普通券 vs 秒杀券的区别**

`tb_voucher.type`：`0 = 普通券，1 = 秒杀券`（默认 0，`hmdp.sql:250`）。分类的作用不在字段本身，而在「**多一张表、多一条链路**」：

```text
普通券（type=0）                      秒杀券（type=1）
tb_voucher 里有一行                   tb_voucher 里有一行
tb_seckill_voucher 里没有行           tb_seckill_voucher 里多一行（一对一）
                                      存 stock / begin_time / end_time
列表查询时 stock/begin/end = null     这三个字段由 LEFT JOIN 带出，有值
没有 Redis 库存 Key                   建券时写 seckill:stock:{id}
不走秒杀链路                          走秒杀链路：Lua 预扣 → MQ 异步下单 →
                                      一人一单 → 超时关单 → 对账补偿
```

代码里判断「是不是秒杀券」主要看两件事，而不是读 `type` 字段：

- `tb_seckill_voucher` 有没有对应行——详情接口没有就返回「该优惠券不是秒杀券」（`VoucherServiceImpl.java:114-117`）；
- Redis 里 `seckill:stock:{id}` 是否存在——不存在时 Lua 返回 3（库存未初始化）。

`type` 字段主要给前端区分展示（「立即抢购」还是「立即领取」用）；后端的分岔靠「建券时走的哪个接口」：`POST /voucher/seckill` → `addSeckillVoucher`（建秒杀券 + 初始化库存），`POST /voucher` → `addVoucher`（只建普通券）。

注意：本地缓存里存的是**对象引用**，`LocalDateTime` 也是对象、不是字符串；只有写进 Redis 时才通过 `JSONUtil.toJsonStr` 变成字符串。

**4.1 补充（一·续）：券和店铺是什么关系（为什么两个缓存用不同的 key）**

一张券**属于某个店铺**，不能跨店使用——这个关系由 `tb_voucher.shop_id` 记录：

```text
券 10：属于店铺 1，标题“100元代金券”
券 11：属于店铺 1，标题“50元秒杀券”
券 12：属于店铺 2，标题“200元代金券”

→ 店铺 1 的券只能在店铺 1 用；店铺 2 的券只能在店铺 2 用
```

用户的使用路径，决定了两次查询查的是两个维度：

```text
用户点进店铺 1
   │
   ▼
前端请求 GET /voucher/list/1
   │  后端查“店铺 1 有哪些券”
   ▼
返回 [券10, 券11]
   │
   ▼
列表缓存按 shopId：cache:voucher:list:1 = [券10, 券11]
                  cache:voucher:list:2 = [券12]

用户选中券 10，点秒杀
   │
   ▼
请求 POST /voucher-order/seckill/10
   │  后端扣“券 10 的库存”
   ▼
库存按 voucherId：seckill:stock:10 = "100"
                 seckill:stock:11 = "200"
```

- **列表按 shopId**：入口是店铺（用户先点店铺才看到券），查的是「一个店铺的券集合」；
- **库存按 voucherId**：入口是券（抢的是某一张具体的券），库存是券自己的属性；
- 一句话：**两个维度，对应两个场景**。

两个容易说错的点：

1. 券 → 店铺的绑定只存在 `tb_voucher.shop_id` 一处。订单表 `tb_voucher_order` 里没有 shop_id（列只有 id、user_id、voucher_id、pay_type、status、时间字段），「只能在对应店铺用」是由券自身的归属决定的，代码里并没有额外的跨店校验；
2. 本项目真正实现的「领券」只有**秒杀下单**这一条路径；普通券（type=0）没有独立的领取/核销接口。所以流程里「用户领了券 10」在代码层面 = 「对券 10 秒杀下单成功」。

**4.1 补充（二）：构建参数怎么读**

```java
Caffeine.newBuilder()                          // 拿到“配置器”
        .maximumSize(1000)                     // 最多存 1000 个 key；放第 1001 个时按 W-TinyLFU 淘汰旧 key
        .expireAfterWrite(5, TimeUnit.SECONDS) // 距“最后一次写入/覆盖”满 5 秒自动过期（读不会续命）
        .build();                              // 生成 Cache 实例（≈ 一个线程安全的 Map）
```

| 构建方法               | 含义            | 本项目取值 | 不设置的后果        |
| ------------------ | ------------- | ----- | ------------- |
| `maximumSize`      | 最多多少个 key     | 1000  | key 基数大时内存被撑爆 |
| `expireAfterWrite` | 写入后多久过期（读不续期） | 5 秒   | 多实例不一致窗口无限大   |
| `build`            | 生成 Cache 实例   | ——    | ——            |

另一个常见配置是 `expireAfterAccess`（最后一次**访问**后多久过期，读会续期）。本项目选 `expireAfterWrite`：**不管读多少次，写入后 5 秒必定刷新**，才能把多实例不一致窗口锁死在 5 秒；`expireAfterAccess` 在高频读下可能永远不刷新，不适合做一致性收敛。

**4.2 券列表读路径：三层顺序查找**（`VoucherServiceImpl.java:54-78`）

```java
@Override
public Result queryVoucherOfShop(Long shopId) {
    // L1：本进程内存
    List<Voucher> local = localVoucherCache.getIfPresent(shopId);
    if (local != null) {
        return Result.ok(local);                    // 命中直接返回，不走网络
    }

    // L2：Redis
    String redisKey = RedisConstants.CACHE_VOUCHER_LIST_KEY + shopId;   // cache:voucher:list:1
    String cached = stringRedisTemplate.opsForValue().get(redisKey);
    if (cached != null && !cached.isEmpty()) {
        List<Voucher> vouchers = JSONUtil.toList(cached, Voucher.class);
        localVoucherCache.put(shopId, vouchers);    // 回填 L1
        return Result.ok(vouchers);
    }

    // L3：MySQL，回填 L2 + L1
    List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
    stringRedisTemplate.opsForValue().set(
            redisKey,
            JSONUtil.toJsonStr(vouchers),
            RedisConstants.CACHE_VOUCHER_LIST_TTL,  // Redis 30 秒
            TimeUnit.SECONDS
    );
    localVoucherCache.put(shopId, vouchers);        // Caffeine 5 秒
    return Result.ok(vouchers);
}
```

**4.2 补充：逐行拆解（每个 API 在干什么）**

```java
@Override
public Result queryVoucherOfShop(Long shopId) {      // ① 入参 shopId，例：1

    List<Voucher> local = localVoucherCache.getIfPresent(shopId);
    // ② getIfPresent = “存在才取”：
    //    key 在缓存且未过期 → 返回 List<Voucher>
    //    key 不在 / 已过期 / 被淘汰 → 返回 null（不报错、也不会去查库）
    //    类似 Map.get()，区别是它会正确处理“过期”和“容量淘汰”

    if (local != null) {
        return Result.ok(local);                     // ③ L1 命中：包装成统一响应体直接返回
    }

    String redisKey = RedisConstants.CACHE_VOUCHER_LIST_KEY + shopId;
    // ④ 拼 Redis Key：常量 "cache:voucher:list:" + 1 → "cache:voucher:list:1"

    String cached = stringRedisTemplate.opsForValue().get(redisKey);
    // ⑤ Redis GET：返回 JSON 字符串或 null；这就是 L2

    if (cached != null && !cached.isEmpty()) {       // ⑥ null=没缓存；空串=防御性判断（正常逻辑不会写入空串）
        List<Voucher> vouchers = JSONUtil.toList(cached, Voucher.class);
        // ⑦ JSON 数组字符串 → List<Voucher> 对象
        localVoucherCache.put(shopId, vouchers);
        // ⑧ 回填 L1：下次同实例请求在 ② 命中；put 对已存在 key 是覆盖
        return Result.ok(vouchers);
    }

    List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
    // ⑨ L3 回源 MySQL：getBaseMapper() 是 MyBatis-Plus 提供的“当前 Service 对应的 Mapper”，
    //    这个方法名映射到 VoucherMapper.xml 里的 <select id="queryVoucherOfShop">

    stringRedisTemplate.opsForValue().set(
            redisKey,                                // cache:voucher:list:1
            JSONUtil.toJsonStr(vouchers),            // List → JSON 字符串
            RedisConstants.CACHE_VOUCHER_LIST_TTL,   // 30
            TimeUnit.SECONDS                         // 命令：SETEX cache:voucher:list:1 30 '<json>'
    );
    // ⑩ 写 L2（带 TTL 30 秒）

    localVoucherCache.put(shopId, vouchers);         // ⑪ 写 L1（5 秒后过期）
    return Result.ok(vouchers);
}
```

**API 速查表**：

| 代码                                  | 属于           | 作用                      | 返回            |
| ----------------------------------- | ------------ | ----------------------- | ------------- |
| `localVoucherCache.getIfPresent(k)` | Caffeine     | 只查不加载                   | 命中=值，未命中=null |
| `localVoucherCache.put(k, v)`       | Caffeine     | 写入/覆盖，开始计 5 秒过期         | ——            |
| `localVoucherCache.invalidate(k)`   | Caffeine     | 删本实例这一条                 | ——            |
| `opsForValue().get(k)`              | Redis        | GET                     | String 或 null |
| `opsForValue().set(k, v, t, unit)`  | Redis        | SETEX：写入并带过期时间          | ——            |
| `stringRedisTemplate.delete(k)`     | Redis        | DEL                     | Boolean       |
| `JSONUtil.toList(json, Class)`      | Hutool       | JSON 数组 → List          | List&lt;T&gt; |
| `JSONUtil.toJsonStr(obj)`           | Hutool       | 对象 → JSON 字符串           | String        |
| `getBaseMapper()`                   | MyBatis-Plus | 拿到当前 Service 对应的 Mapper | Mapper        |

两个容易混的 Caffeine API：

```java
getIfPresent(key)                 // 只查，未命中返回 null —— 本项目用这个（加载链是 Redis→MySQL 两层，手动写更清楚）
get(key, k -> 加载函数)            // 未命中就调函数加载并自动回填 —— 本项目没用
```

**4.3 写失效：先库后删**（`VoucherServiceImpl.java:143-157` 全文，①~⑤ 为实际执行顺序）

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void addSeckillVoucher(Voucher voucher) {
    // ① 写 MySQL：保存券（tb_voucher）
    save(voucher);
    // ② 写 MySQL：保存秒杀信息（tb_seckill_voucher：库存、起止时间）
    SeckillVoucher seckillVoucher = new SeckillVoucher();
    seckillVoucher.setVoucherId(voucher.getId());
    seckillVoucher.setStock(voucher.getStock());
    seckillVoucher.setBeginTime(voucher.getBeginTime());
    seckillVoucher.setEndTime(voucher.getEndTime());
    seckillVoucherService.save(seckillVoucher);
    // ③ 写 Redis：预热秒杀库存（业务数据，不是缓存）
    stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
    // ④ 删 L1：只删本实例的 Caffeine
    localVoucherCache.invalidate(voucher.getShopId());
    // ⑤ 删 L2：删 Redis 缓存
    stringRedisTemplate.delete(RedisConstants.CACHE_VOUCHER_LIST_KEY + voucher.getShopId());
}
```

顺序：**① 写 MySQL → ② 写 MySQL → ③ 写 Redis 库存 → ④ 删 L1 → ⑤ 删 L2**；「先库后删」= 缓存删除一定发生在数据库写入之后。

**4.3 补充：五个动作分别操作哪个存储（MySQL / Redis / Caffeine）**

先认识这次操作涉及的三个存储：

| 存储       | 是什么                | 存什么                                       | 特点               |
| -------- | ------------------ | ----------------------------------------- | ---------------- |
| MySQL    | 外部关系数据库            | 券本体、秒杀信息（行 = 记录，列 = 字段）                   | 最终数据源，永久保存，有事务回滚 |
| Redis    | 外部 key-value 数据库   | 秒杀库存（String）、券列表缓存（String JSON）           | 快；**没有回滚**       |
| Caffeine | 本应用 JVM 内存里的一张 Map | 店铺券列表（key=shopId → value=List\<Voucher\>） | 最快；只在本机有效        |

然后逐行看（①~⑤）：

**① `save(voucher)` → 写 MySQL 表 `tb_voucher`**

```text
tb_voucher（一行 = 一张券）：
id | shop_id | title       | type | status | ...
10 | 1       | 100元代金券  | 1    | 1      | ...
```

**② `seckillVoucherService.save(seckillVoucher)` → 写 MySQL 表 `tb_seckill_voucher`**

```text
tb_seckill_voucher（一行 = 一张秒杀券的库存/活动时间，靠 voucher_id 与①一对一）：
voucher_id | stock | begin_time | end_time
10         | 100   | ...        | ...
```

① 和 ② 在同一个事务里（方法上有 `@Transactional`）：要么都成功，要么一起回滚。

**③ `set(SECKILL_STOCK_KEY + id, stock.toString())` → 写 Redis String**

```text
key   = "seckill:stock:" + 10 = seckill:stock:10
value = 100.toString()        = "100"
类型  = String（普通字符串，不是 Hash / Set）

写入后 Redis 里多一条：
seckill:stock:10 → "100"
```

- 大白话：把库存从 MySQL 复制一份到 Redis（预热），秒杀时直接扣 Redis、不打 MySQL；
- 这是业务数据、不是缓存；Redis 没有「数字类型」，value 存的是数字的字符串形式。

**④ `localVoucherCache.invalidate(voucher.getShopId())` → 操作 Caffeine（本机 Map）**

```text
localVoucherCache = JVM 内存里的 Map：key = shopId，value = 该店铺券列表

删之前：                  删之后：
1 → [券10, 券11]    →     （key=1 这条没了）
2 → [券12]               2 → [券12]
```

- `invalidate(1)` = 把 key=1 这条从本机 Map 里删掉；key 不存在也不会报错；
- 只影响本实例，其他机器的 Caffeine 里可能还有旧列表（靠 5 秒 TTL 自己收敛）。

**⑤ `delete(CACHE_VOUCHER_LIST_KEY + shopId)` → 写 Redis String（内容是 JSON）**

```text
key = "cache:voucher:list:" + 1 = cache:voucher:list:1

删之前 value = '[{"id":10,"title":"100元代金券"},...]'   （String，内容是 JSON）
delete 之后  = 这个 key 整个消失
```

- `delete` 不关心 value 是什么结构，直接把 key 删掉；
- 目的：下次读店铺 1 的券列表时缓存未命中 → 重新查 MySQL → 拿到包含新券的最新列表再写回缓存。

五个动作全部执行完后的「现场」：

```text
MySQL
  tb_voucher:         (id=10, shop_id=1, title="100元代金券", type=1, status=1)
  tb_seckill_voucher: (voucher_id=10, stock=100, begin_time=..., end_time=...)

Redis
  seckill:stock:10      = "100"     ← ③ 写入
  cache:voucher:list:1  = 不存在     ← ⑤ 删掉

Caffeine（本 JVM）
  key=1 → 已被 ④ 删掉
```

三个容易被追问的点（实现边界）：

1. **Redis / Caffeine 不在 MySQL 事务里**：①② 失败会一起回滚；但若 ③④⑤ 之后抛异常，③ 写进 Redis 的库存不会自动撤销，只能靠重试/补偿（MySQL 与 Redis 之间没有事务）；
2. **④⑤ 发生在 MySQL 提交之前**（它们还在 `@Transactional` 方法体内）：并发下存在「删完缓存、别人趁新券未提交把旧列表写回缓存」的小窗口；严谨的 Cache Aside 是事务提交后再删（或延迟双删）；
3. **`seckill:stock:10` 没有 TTL**：活动结束后不会自动消失，需要人工或定时任务清理。

#### 5. 为什么是 Caffeine + Redis 两级（选型对比）

| 方案                   | 决定性因素                           | 判定    |
| -------------------- | ------------------------------- | ----- |
| 只用 MySQL             | 热点列表反复回源，数据库压力大                 | 淘汰    |
| 只用本地 Caffeine        | 快，但多实例不共享、容量有限、重启即失效            | 淘汰    |
| 只用 Redis             | 跨实例共享，但极热 Key 仍要每次走网络           | 留作 L2 |
| **Caffeine + Redis** | L1 抗单实例热读，L2 保跨实例共享，短 TTL 收敛不一致 | 采用    |

**本地缓存库选型：Caffeine vs Guava Cache（为什么不用 Guava）**

Guava Cache 也能做一级缓存——两者其实是"同一个作者、一前一后"的关系（Guava Cache 作者 Ben Manes 后来写了 Caffeine），API 很像、迁移成本低。但对比后选 Caffeine：

| 维度 | Caffeine（选） | Guava Cache |
| --- | --- | --- |
| 淘汰算法 | W-TinyLFU：window + 频次准入，抗扫描、命中率高 | 分段 LRU：只看最近访问，扫描型流量容易把热数据冲掉（见第 6 节） |
| 并发性能 | 无锁读 + 写缓冲 + 异步维护，读多写少吞吐更高 | 分段锁，并发升高后性能落后 |
| 内存开销 | 节点结构紧凑 + Count-Min Sketch 近似计数，同样容量更省内存 | 精确计数与结构开销更大 |
| 维护状态 | 持续活跃 | 基本冻结，Guava 官方推荐迁移到 Caffeine |
| 生态支持 | Spring Boot 原生支持，版本由 Boot 统一管理（`com.github.ben-manes.caffeine:caffeine`） | Spring Framework 5 / Boot 2 起已移除 GuavaCacheManager 自动配置 |

一句话：Guava 的短板是"算法老（LRU 不抗扫描）+ 组件停更 + 生态退出"；而我们的场景恰恰是热点集中、还叠加爬虫扫描流量，Caffeine 的 W-TinyLFU 正好打这个痛点，所以选 Caffeine。

#### 6. Caffeine 的淘汰算法：W-TinyLFU（面试常问）

一句话本质：**缓存放满了要踢人时，不是问"谁最久没用"（LRU），也不是问"谁用得最少"（LFU），而是让"新来的"和"马上要被踢的"当场 PK 访问次数——谁次数多谁留下。**

**三个区 = 三种待遇：**

| 区 | 谁在里面 | 被淘汰的概率 |
| --- | --- | --- |
| window（窗口区） | 刚进来的新数据 | 最高——"试用期" |
| probation（试用区） | 从窗口 PK 赢进来的、从保护区降下来的 | 中等——正在考察 |
| protected（保护区） | 在 probation 里被再次访问过的 | 最低——确认是热数据 |

**一个 key 的完整一生：**

1. **新数据先进 window**：第一次查券 A → 回填进 window，速记本给它记上"访问 1 次"；
2. **window 满了就 PK**：把 window 里最久没用的挤出来当"候选人"，和主区 probation 里最冷的"受害者"比访问次数——候选人次数更高才准进主区，否则直接扔掉；
   - 抗扫描就发生在这里：爬虫顺序扫 10 万条数据（各访问 1 次）在 PK 里全输；真热 key（访问 100 次）赢进主区；
3. **probation 里被再次访问 → 晋升 protected**（保护区），从此基本不会被淘汰；
4. **protected 满了 → 把最久没用的降级回 probation**；再没人访问就成为下次 PK 的受害者，最终被踢出——不存在"永远赖着不走"的数据（这是它优于纯 LFU 的地方）。

**"比次数"用的速记本：Count-Min Sketch：**

- 不可能给每个 key 存精确计数器（内存扛不住），所以用一个很小的位图 + 几个哈希函数**近似**记录访问次数，允许一点误差；
- **计数会老化**：总访问量达到容量 10 倍时全体计数减半——三个月前热、现在没人用的 key 历史优势被抹平，新热 key 才进得来（解决 LFU 的"历史包袱"）。

**window 的大小还会自适应**：Caffeine 盯着命中率做"爬山试探"——调窗口大小，命中率变好就继续、变差就回调。窗口太小，新 key 没机会攒访问记录；太大，又退化成纯 LRU。

**对照记：**

| 算法 | 看什么 | 致命伤 | Caffeine 的对策 |
| --- | --- | --- | --- |
| LRU | 最近用没用 | 怕扫描（扫一遍全冲走） | window + 频次 PK：扫描数据进不了主区 |
| LFU | 用得多少 | 怕历史包袱 + 计数器费内存 | Count-Min Sketch 近似 + 计数老化减半 |
| **W-TinyLFU** | 两者都要 | —— | window（管新）+ 准入 PK（管质量）+ 老化（管变化） |

**落到项目**：`maximumSize(1000)` 决定"满了淘汰谁"（W-TinyLFU 负责）；`expireAfterWrite(5, TimeUnit.SECONDS)` 是 TTL，到期直接失效、不参与 PK——两者是两件事。

> 别背成"把 HashMap 分成三块、三个区都按命中率自适应"：存储（ConcurrentHashMap 索引）和淘汰（三条访问顺序队列）是两套结构；按命中率自适应的是 **window 区**，protected / probation 是相对固定的 SLRU 分割。

#### 7. 边界（面试追问）

| 追问                    | 回答                                                                                  |
| --------------------- | ----------------------------------------------------------------------------------- |
| 多实例下 L1 不一致怎么办？       | 本实例写后主动 `invalidate` 自己；其他实例靠 5 秒 TTL 收敛，**未实现跨实例失效广播**，更严格可上 Redis Pub/Sub 或 MQ 广播 |
| 为什么 TTL 是 5 秒 + 30 秒？ | 5 秒把多实例不一致窗口压到可接受范围；30 秒兜住「删缓存失败」等异常，避免缓存长期脏                                        |
| 库存为什么不做缓存？            | 高频变化且直接影响秒杀，缓存旧库存会误导；所有返回路径都用 `fillLiveStock` 实时读 `seckill:stock:{id}`              |
| 本地缓存会不会打爆内存？          | `maximumSize(1000)` + 5 秒过期，Key 是 shopId / voucherId，量级可控；Key 基数更大时要按热 Key 评估或换分片   |
| 查不到数据会怎样？             | 返回空列表并写入两级缓存（Redis 存 `[]`，TTL 30 秒），相同 shopId 直接命中，也顺带防穿透                           |
| 新增普通券为什么没失效？          | `addVoucher` 直接 `save`，未删缓存，依赖 30 秒 TTL 自然过期；生产应把写操作统一收口到失效逻辑（实现边界，不夸大）             |
| 这是强一致吗？               | 不是。允许 5~30 秒旧值，属于最终一致；库存等强一致数据被排除在缓存之外                                              |
| 有命中率 / 性能数据吗？         | 没有独立的压测对比数据，简历只写「减少访问压力」，不写具体百分比                                                    |

---


## 五、流程编排与可扩展性重构

> 简历第 5 条是**结构重构**，对应四节：**5.1 责任链**（订单消费链路：幂等 → 用户锁 → 用户券查重 → 扣库存 → 落库；`setNext` 串链 + **装配期静态判环** + 结果枚举化）；**5.2 规则树**（对账处置：根节点扫描 → 查订单 → 三个分支节点；**运行期**按对象身份判环 + 可配置步数上限）；**5.3 可测试性**（新增分支不改主流程、节点可独立单测）；**5.4 两套怎么选 + 搬迁指南**（对比表、只改三处、五条铁律、以及本项目从列表版走到链表版的取舍）。这一条不引入新的业务能力，而是把前四条里的两段脆弱流程变成可扩展、可自检的结构。

### 5.1 责任链：订单消费链路重构（幂等 → 用户锁 → 扣库存 → 落库）

> **简历原文（第 5 条前半）**：将订单消费链路重构为责任链（幂等 → 用户锁 → 扣库存 → 落库）。

**面试话术（四段式：背景 → 剖析 → 构思 → 复盘，可直接背）**

> **① 背景阐述**：秒杀订单不是同步落库的，MQ 消费端拿到消息之后要做一串动作：订单 ID 幂等、按用户加锁、用户券业务查重、数据库条件扣库存、订单落库。改造前这五步全写在 `VoucherOrderServiceImpl.createVoucherOrder` 一个方法里，37 行（含 `@Override` / `@Transactional`），一个 `try/finally` 包着，锁的获取和释放在方法头尾。
>
> **② 问题剖析**：这段代码功能上没问题，问题在**演进成本和定位成本**。第一，要新增一步校验（风控、限购、黑名单），必须往方法中间插代码，插入点还在 `try` 块里，改完要重测整条消费链路——秒杀链路是要求最稳的地方，每次动它都要重新走一遍 MQ + Redis + MySQL 的回归。第二，五步的失败语义靠方法里的 `return` / `throw` 表达，混在一起：哪个 `return` 是"正常提前结束"，哪个 `throw` 是"整条事务回滚"，读代码要一句句分辨。第三，想单独验证"库存扣减影响 0 行时会不会回滚"，必须先起 Spring 容器 + Redis + MySQL + MQ 全套，因为没有一个可以独立 `new` 出来的单元。
>
> **③ 构思实现**：把"固定顺序、一步不过就断"的流程抽成责任链。框架只有两个类型：`ChainNode`（抽象节点——持 `next` 指针、`setNext` 串链、`next()` 接力，`apply()` 是唯一的业务方法）+ `Chain`（执行器——持链头，负责一次调用的入口）。三条约定：**校验通过 → 调 `next()` 交棒；命中幂等 → 不接力、直接 `return` 结果；出错 → 抛异常**。结果类型是 `OrderCreateOutcome` 枚举（`CREATED` / `SKIPPED_DUPLICATE_ORDER` / `SKIPPED_DUPLICATE_USER_VOUCHER`），比原来的 `boolean` 多了一维可观测信息——"为什么提前结束"能直接进日志。**判环放在装配期**：链的下一跳由 `setNext` 决定、不依赖运行时数据，是静态结构，所以 `Chain.assemble(head)` 在启动时遍历一遍 `next` 指针做判环，重访即抛异常并打印环路路径（如 `a -> b -> a`）；`setNext` 只允许调用一次，运行期结构不可再改，因此 `execute` 零额外开销。（对比规则树：下一跳由 `next()` 按数据决定，是动态结构，必须每轮运行都判环。）订单侧五个节点各自是 Spring 单例，数据统一放 `OrderCreateContext`，节点自己不存请求级状态；顺序只在 `OrderCreateChainConfig.orderCreateChain` 里 `setNext` 串一次。编排层只留四件事：开事务、`new Context`、`execute`、`finally` 释放锁。行为口径逐条对齐：`@Transactional(rollbackFor = Exception.class)` 不动，`RuntimeException` 直接抛、受检异常包成 `IllegalStateException`，失败统计与 MQ 重试口径不变。
>
> **④ 复盘总结**：一句话——把"固定顺序的校验流水线"从过程代码变成显式的链式装配，收益是**新增一步只加节点 + 装配处插一行，老节点零回归**；代价是多一层抽象（+9 个类：框架 2 + 装配与上下文 2 + 节点 5，调用栈多一层）。顺手把和规则树的分工说清楚：链管固定顺序的检验型流水线，树管按状态分叉的处置决策——这也是同一个项目里两种模式并存的原因。

#### 1. 这条在说什么

- 位置：RocketMQ 消费端，`VoucherOrderServiceImpl.createVoucherOrder`（`VoucherOrderServiceImpl.java:134-148`），由 `SeckillOrderListener.onMessage` 调用（`SeckillOrderListener.java:44`）。
- 链上真实节点是 **5 个**：订单 ID 幂等 → 用户锁 → 用户+券查重 → 条件扣库存 → 落库。简历把它概括成 4 步（"幂等 → 用户锁 → 扣库存 → 落库"），**面试要按 5 个节点讲**，其中两个幂等节点的职责完全不同，这也是最容易被追问的一处：

| 幂等节点 | 判据 | 拦住的是什么 | 代码 |
|---|---|---|---|
| `OrderIdempotentNode` | `selectById(order.getId()) != null` | 同一条消息被 MQ 重复投递（订单 ID 相同） | `OrderIdempotentNode.java:23-26` |
| `UserVoucherIdempotentNode` | `selectCount(userId + voucherId + status <> 4) > 0` | 同一用户同一张券的另一条消息（订单 ID 不同），含对账重投、用户重复发起 | `UserVoucherIdempotentNode.java:25-33` |

- 三件事必须分清：**谁提前结束**（节点不调 `next()`、直接 `return` 结果枚举）、**谁中断链路**（节点抛异常）、**谁释放资源**（编排层 `finally`）。
- 与其它章的关系只在此一句：Redis Lua 预扣决定"谁能进 MQ"（见第一章），这一章只讲"进了 MQ 之后，消费端怎么组织这五步"。

#### 2. 重构前：问题出在哪（真实代码）

下面是改造前的实现全文，来自 `git show dc94a94`（`refactor(order): extract order creation into responsibility chain`，2026-10-06）删除掉的那一段，被删代码无现行行号可标：

```java
// 改造前：VoucherOrderServiceImpl#createVoucherOrder 全文（dc94a94 已删除）
@Override
@Transactional(rollbackFor = Exception.class)
public void createVoucherOrder(VoucherOrder voucherOrder) {
    if (getById(voucherOrder.getId()) != null) {
        log.info("重复消费订单消息，按订单 ID 幂等返回 orderId={}", voucherOrder.getId());
        return;                                        // 出口①：幂等提前结束（在 try 之外）
    }

    Long userId = voucherOrder.getUserId();
    RLock lock = redissonClient.getLock("lock:order:" + userId);
    boolean locked = lock.tryLock();
    if (!locked) {
        throw new IllegalStateException("用户订单并发锁获取失败");   // 出口②：未拿到锁
    }
    try {
        long duplicated = count(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherOrder.getVoucherId())
                .ne(VoucherOrder::getStatus, 4));   // 已取消订单不算重复：取消已释放库存与资格，允许重新抢
        if (duplicated > 0) {
            log.info("重复消费用户券消息，按用户和券幂等返回 userId={}, voucherId={}",
                    userId, voucherOrder.getVoucherId());
            return;                                    // 出口③：业务幂等提前结束（在 try 之内）
        }

        boolean stockUpdated = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                        .gt(SeckillVoucher::getStock, 0)
                        .setSql("stock = stock - 1")
        );
        if (!stockUpdated) {
            throw new IllegalStateException("数据库库存与 Redis 预扣状态不一致");  // 出口④：扣减失败
        }
        save(voucherOrder);                            // 出口⑤：正常落库
    } finally {
        lock.unlock();                                 // 锁释放埋在方法尾部
    }
}
```

问题清单：

| # | 问题 | 具体表现 | 后果 |
|---|---|---|---|
| 1 | 扩展要动主干 | 新增一步校验只能插进 `try` 块中间 | 每次改动都要重测整条消费链路，秒杀链路回归成本最高 |
| 2 | 出口靠位置区分 | 5 个出口里 ①③（`return`）是"正常结束"、②④（`throw`）是"要回滚"，能分清只因为读到了 `try/finally` 的边界 | 新同事加一步极易把"提前结束"写成 `throw`，或反过来 |
| 3 | 一个方法兼两种关注点 | 编排（事务、锁、异常口径）与业务步骤（幂等、扣减、落库）写在同一层 | 改锁的粒度、改事务注解都会碰到业务代码 |
| 4 | 失败定位靠行号 | 出问题只能看堆栈落在方法第几行 | 同一条日志（如"扣库存失败"）出现时，上下文要靠人脑还原 |
| 5 | 无法单测 | 步骤是私有过程代码，没有可独立构造的单元 | 只测一条分支也要起 Spring + Redis + MySQL + MQ |

#### 3. 重构后：责任链完整执行步骤（带具体执行数据）

示例：券 1001（库存 100）、用户 2 抢券成功，Redis 预扣已写 `seckill:reservation:1001:2`，队列里是一条消息 `{"id":1758000000000000001,"userId":2,"voucherId":1001}`。

```mermaid
flowchart TD
    A["SeckillOrderListener.onMessage<br/>JSONUtil.toBean 转 VoucherOrder"] --> B["VoucherOrderServiceImpl.createVoucherOrder<br/>@Transactional 开事务 + new OrderCreateContext"]
    B --> C{"1 OrderIdempotentNode<br/>selectById 命中同一订单 ID ?"}
    C -->|"命中"| Z["不接力，返回 SKIPPED_DUPLICATE_ORDER<br/>链路结束，事务正常提交，无任何写操作"]
    C -->|"未命中"| D["2 UserLockNode<br/>getLock 锁 Key 为 lock:order:加 userId 后 tryLock"]
    D -->|"未拿到锁"| X["throw IllegalStateException<br/>事务回滚，MQ 稍后重投"]
    D -->|"拿到锁并写入 Context"| E{"3 UserVoucherIdempotentNode<br/>同用户同券存在 status 不等于 4 的订单 ?"}
    E -->|"存在"| Z2["不接力，返回 SKIPPED_DUPLICATE_USER_VOUCHER<br/>finally 释放锁，事务提交"]
    E -->|"不存在"| F["4 StockDeductNode<br/>条件更新 stock 减 1 且要求 stock 大于 0"]
    F -->|"影响 0 行"| X2["throw IllegalStateException<br/>事务回滚"]
    F -->|"影响 1 行"| G["5 SaveOrderNode<br/>insert tb_voucher_order"]
    G --> H["链正常走完，方法返回<br/>finally 释放锁，事务提交"]
    H --> I["回到 Listener：syncSend 延迟关单消息<br/>delay level 5，消费完成 ACK"]
    Z --> J["消费完成 ACK"]
    Z2 --> J
```

逐步执行与数据变化：

| 步骤 | 谁在做 | 具体动作（真实调用） | 数据变化 |
|---|---|---|---|
| 0 | `SeckillOrderListener.java:43-44` | `JSONUtil.toBean` 反序列化 → 只有 `id/userId/voucherId` 三个字段的订单对象 → 调用 `createVoucherOrder` | 无 |
| 0.5 | `VoucherOrderServiceImpl.java:135-137` | Spring 事务代理开事务（`rollbackFor = Exception.class`）；`new OrderCreateContext(voucherOrder)` | 无 |
| 1 | `OrderIdempotentNode.java:22-27` | `voucherOrderMapper.selectById(1758000000000000001)` → `null` → 返回 `true` 继续 | 无 |
| 2 | `UserLockNode.java:23-29` | `redissonClient.getLock("lock:order:2")` → `tryLock()` 成功 → `context.setUserLock(lock)` → 返回 `true` | `lock:order:2` 被持有（Redisson 看门狗续期） |
| 3 | `UserVoucherIdempotentNode.java:24-34` | `selectCount(user_id=2 and voucher_id=1001 and status<>4)` → `0` → 返回 `true` | 无（只读） |
| 4 | `StockDeductNode.java:23-33` | `update ... eq(voucherId,1001).gt(stock,0).setSql("stock = stock - 1")` → 影响 1 行 → 返回 `true` | `tb_seckill_voucher.stock` 100 → 99 |
| 5 | `SaveOrderNode.java:20-22` | `voucherOrderMapper.insert(order)` → 返回 `true` | `tb_voucher_order` 新增 1 行，`status=1` |
| 6 | `VoucherOrderServiceImpl.java:145-147` | `finally` → `context.releaseLock()` → `isHeldByCurrentThread()` 为真 → `unlock()` | `lock:order:2` 释放 |
| 7 | 事务提交后回到 `SeckillOrderListener.java:45-50` | `syncSend(order-timeout-topic, orderId, 3000, delayLevel=5)` | 延迟消息 1 条，用于超时关单（细节见第四章） |

两条"非正常出口"的走向，面试必须能顺口说出：

| 场景 | 停在哪个节点 | 链的行为 | 事务与资源 | 谁兜底 |
|---|---|---|---|---|
| 同一订单 ID 重复消费 | 节点 1 | 不接力，返回 `SKIPPED_DUPLICATE_ORDER`，节点 2~5 一个都不执行 | 事务正常提交（本来就没写操作）；`releaseLock()` 因 `userLock == null` 空转（`OrderCreateContext.java:20-24`） | 无需兜底 |
| 同用户同券已有有效订单 | 节点 3 | 不接力，返回 `SKIPPED_DUPLICATE_USER_VOUCHER`，节点 4~5 不执行 | 事务正常提交；锁已在 `finally` 释放 | 无需兜底 |
| 数据库库存扣减影响 0 行 | 节点 4 | `throw IllegalStateException("数据库库存与 Redis 预扣状态不一致")` | 事务回滚；Redis 预扣仍在 → 由对账任务处置（见 5.2） | 消费失败由 MQ 重试 + 对账兜底 |
| 未拿到用户锁 | 节点 2 | `throw IllegalStateException("用户订单并发锁获取失败")` | 事务回滚 | MQ 重试 |

#### 4. 核心代码：从骨架到业务（框架可整包搬走）

> 读法：**4.1、4.2 是框架骨架**（`ChainNode` + `Chain`，共 151 行，去掉业务后原样可搬）；**4.3 上下文**；**4.4 节点示例**（口诀：**查 → 拦 → 写上下文 → 决定是否接力**）；**4.5 装配 + 判环**；**4.6 调用**；**4.7 执行链路**；**4.8 为什么判环放在装配期**。

**4.1 框架骨架①：节点契约（链表版）**（`framework/chain/ChainNode.java` 全文，64 行）

```java
public abstract class ChainNode<T, C, R> {

    /** 下一跳：装配期由 setNext 注入一次，之后只读 */
    private ChainNode<T, C, R> next;

    /** 串链：只在启动装配时调用一次，返回下一跳以便链式书写 */
    public ChainNode<T, C, R> setNext(ChainNode<T, C, R> next) {
        if (this.next != null) {
            throw new IllegalStateException("节点 " + name() + " 的 next 已装配过，不允许重复串链");
        }
        this.next = next;
        return next;
    }

    /** 接力棒：中间节点校验通过后调用；终点节点不调用 */
    protected R next(T request, C context) throws Exception {
        if (next == null) {
            throw new IllegalStateException("节点 " + name() + " 没有下一跳却调用了 next()，请检查装配顺序");
        }
        return next.apply(request, context);
    }

    /** 本节点业务：查 → 拦 → 写上下文 → 接力（或直接 return 结果表示链到此结束） */
    public abstract R apply(T request, C context) throws Exception;

    /** 节点名称：装配期判环的报错路径展示用，默认取类名，可重写 */
    public String name() {
        return getClass().getSimpleName();
    }

    /** 仅供 Chain.assemble 在装配期遍历判环使用 */
    final ChainNode<T, C, R> nextNode() {
        return next;
    }
}
```

三条约定（被问"你的链怎么表达结束"就答这个）：

| 情况 | 节点怎么做 | 语义 |
| --- | --- | --- |
| 校验通过 | `return next(request, context)` | 把接力棒交给下一跳 |
| 命中幂等 / 无需继续 | **不调 `next()`**，直接 `return` 一个结果 | 链到此结束，**属于正常结束，不是异常** |
| 出错 | `throw` | 中断链路并向上抛，由编排层决定事务回滚与 MQ 重试 |

`setNext` 里那句"已装配过就不许再设"很关键：**装配是启动期行为，链结构在运行期不允许再改**——这是后面"运行期零开销"和"装配期判环一次就够"的前提。

**4.2 框架骨架②：执行器 + 装配期判环**（`framework/chain/Chain.java` 核心，全文 87 行）

```java
/** 装配入口：把串好的链头交进来，做一次静态判环 + 计数 */
public static <T, C, R> Chain<T, C, R> assemble(ChainNode<T, C, R> head) {
    if (head == null) {
        throw new IllegalArgumentException("责任链头节点不能为空");
    }
    Set<ChainNode<T, C, R>> visited = Collections.newSetFromMap(
            new IdentityHashMap<ChainNode<T, C, R>, Boolean>());     // 按对象身份记录，重访即成环
    List<ChainNode<T, C, R>> path = new ArrayList<>();                // 记录路径供报错
    ChainNode<T, C, R> node = head;
    while (node != null) {
        if (!visited.add(node)) {                                     // 走过 → 成环
            throw new IllegalStateException("责任链装配成环：" + loopPath(path, node));
        }
        path.add(node);
        node = node.nextNode();                                       // 沿 next 走一步
    }
    return new Chain<>(head, path.size());
}

/** 执行整条链：从链头开始，结果由终点（或提前结束的节点）产出 */
public R execute(T request, C context) throws Exception {
    return head.apply(request, context);          // 运行期只是转发，零额外开销
}
```

报错长这样（`loopPath` 会从重复节点第一次出现的位置开始拼环路）：`责任链装配成环：a -> b -> a`。

**4.3 上下文：只放"过程中产生、后面还要用"的东西**（`service/order/OrderCreateContext.java` 全文，26 行）

```java
@Data
public class OrderCreateContext {

    private final VoucherOrder order;   // 本次要落库的订单（只读，构造时传入）
    private RLock userLock;             // UserLockNode 获取，编排层 finally 里释放

    /** 释放节点申请的锁；未加锁时是空操作（所以幂等提前结束的分支也能安全调用） */
    public void releaseLock() {
        if (userLock != null && userLock.isHeldByCurrentThread()) {
            userLock.unlock();
        }
    }
}
```

节点本身不持有请求级数据（都是 Spring 单例，成员变量只有 `@Resource` 注入的依赖），一次调用的数据全在 Context 里——这是节点能并发安全的前提。

**4.4 节点示例：四种语义各一个代表**（`OrderIdempotentNode.java:22-29`、`UserLockNode.java:23-31`、`StockDeductNode.java:23-34`、`SaveOrderNode.java:21-24`）

```java
// ① 命中幂等型：不接力，直接返回"跳过"的原因（链到此结束，后续节点一个都不跑）
@Service
public class OrderIdempotentNode extends ChainNode<VoucherOrder, OrderCreateContext, OrderCreateOutcome> {

    @Resource private VoucherOrderMapper voucherOrderMapper;

    @Override
    public OrderCreateOutcome apply(VoucherOrder request, OrderCreateContext context) throws Exception {
        if (voucherOrderMapper.selectById(request.getId()) != null) {          // 查
            log.info("重复消费订单消息，按订单 ID 幂等返回 orderId={}", request.getId());
            return OrderCreateOutcome.SKIPPED_DUPLICATE_ORDER;                 // 不接力 = 提前结束
        }
        return next(request, context);                                        // 接力给下一跳
    }
}
```

```java
// ② 申请资源型：把"要还的东西"写进上下文，自己不负责释放
@Service
public class UserLockNode extends ChainNode<VoucherOrder, OrderCreateContext, OrderCreateOutcome> {

    @Resource private RedissonClient redissonClient;

    @Override
    public OrderCreateOutcome apply(VoucherOrder request, OrderCreateContext context) throws Exception {
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + request.getUserId());
        if (!lock.tryLock()) {
            throw new IllegalStateException("用户订单并发锁获取失败");            // 拿不到锁 = 系统级失败
        }
        context.setUserLock(lock);                                            // 写上下文：交给编排层释放
        return next(request, context);                                        // 接力
    }
}
```

> 为什么锁不在节点里 `unlock`？因为锁的生命周期要覆盖**整条链**（后面的扣库存、落库都在锁内），而节点只负责"自己的那一步"。谁申请、谁的生命周期更长，谁释放——所以放在编排层 `finally`。

```java
// ③ 抛异常型：业务不一致 = 中断链路 + 事务回滚 + MQ 重试
@Service
public class StockDeductNode extends ChainNode<VoucherOrder, OrderCreateContext, OrderCreateOutcome> {

    @Resource private ISeckillVoucherService seckillVoucherService;

    @Override
    public OrderCreateOutcome apply(VoucherOrder request, OrderCreateContext context) throws Exception {
        boolean stockUpdated = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, request.getVoucherId())
                        .gt(SeckillVoucher::getStock, 0)                 // 条件：stock > 0
                        .setSql("stock = stock - 1"));                   // 在 SQL 里做减法
        if (!stockUpdated) {
            throw new IllegalStateException("数据库库存与 Redis 预扣状态不一致");   // 影响 0 行 → 不一致
        }
        return next(request, context);                                   // 接力
    }
}
```

```java
// ④ 终点型：不再接力，直接产出整条链的结果（结果会沿调用栈一层层带回编排层）
@Service
public class SaveOrderNode extends ChainNode<VoucherOrder, OrderCreateContext, OrderCreateOutcome> {

    @Resource private VoucherOrderMapper voucherOrderMapper;

    @Override
    public OrderCreateOutcome apply(VoucherOrder request, OrderCreateContext context) {
        voucherOrderMapper.insert(request);
        return OrderCreateOutcome.CREATED;                                // 终点产出结果，不调 next()
    }
}
```

结果用枚举而不是 `boolean`（`OrderCreateOutcome.java`，20 行）：

| 取值 | 含义 | 谁产出 |
| --- | --- | --- |
| `CREATED` | 正常创建：扣库存 + 落库都完成 | `SaveOrderNode`（终点） |
| `SKIPPED_DUPLICATE_ORDER` | 同一条消息被重复投递（订单 ID 相同） | `OrderIdempotentNode` |
| `SKIPPED_DUPLICATE_USER_VOUCHER` | 同一用户对同一张券已有有效订单 | `UserVoucherIdempotentNode` |

**4.5 装配 + 判环：顺序与防护一次做完**（`service/order/OrderCreateChainConfig.java` 全文，33 行）

```java
@Configuration
public class OrderCreateChainConfig {

    @Bean
    public Chain<VoucherOrder, OrderCreateContext, OrderCreateOutcome> orderCreateChain(
            OrderIdempotentNode orderIdempotentNode,
            UserLockNode userLockNode,
            UserVoucherIdempotentNode userVoucherIdempotentNode,
            StockDeductNode stockDeductNode,
            SaveOrderNode saveOrderNode) {
        // 顺序 = 业务语义：先查重（最便宜）→ 再串行化 → 再业务查重 → 再动数据库资源
        orderIdempotentNode.setNext(userLockNode)
                .setNext(userVoucherIdempotentNode)
                .setNext(stockDeductNode)
                .setNext(saveOrderNode);
        return Chain.assemble(orderIdempotentNode);      // 串完立刻静态判环，写错启动就失败
    }
}
```

**4.6 调用：编排层只留四件事**（`VoucherOrderServiceImpl.java:134-148`）

```java
@Override
@Transactional(rollbackFor = Exception.class)                     // ① 事务边界
public void createVoucherOrder(VoucherOrder voucherOrder) {
    OrderCreateContext context = new OrderCreateContext(voucherOrder);   // ② 一次请求一个上下文
    try {
        OrderCreateOutcome outcome = orderCreateChain.execute(voucherOrder, context);   // ③ 跑链，拿回结果
        log.info("订单创建链执行完成 orderId={}, outcome={}", voucherOrder.getId(), outcome);
    } catch (RuntimeException e) {
        throw e;                                                     // 运行时异常原样抛（保持原口径）
    } catch (Exception e) {
        throw new IllegalStateException("订单创建链执行失败 orderId=" + voucherOrder.getId(), e);
    } finally {
        context.releaseLock();                                       // ④ 资源统一在编排层释放
    }
}
```

**4.7 执行链路（对照代码看）**

```text
createVoucherOrder(order)                                    ← 事务开始，new OrderCreateContext
  → ① OrderIdempotentNode       selectById 命中？
                                 命中 → 不接力，返回 SKIPPED_DUPLICATE_ORDER（事务正常提交）
                                 未命中 → next() → ②
  → ② UserLockNode              tryLock 失败？
                                 失败 → throw（回滚 + MQ 重试）
                                 成功 → 锁写进 Context → next() → ③
  → ③ UserVoucherIdempotentNode 已有有效订单？
                                 有 → 不接力，返回 SKIPPED_DUPLICATE_USER_VOUCHER（finally 释放锁）
                                 无 → next() → ④
  → ④ StockDeductNode           stock-1 影响 0 行？
                                 是 → throw（回滚 + MQ 重试）
                                 影响 1 行 → next() → ⑤
  → ⑤ SaveOrderNode             insert 订单 → return CREATED（终点，不接力）
  → 结果沿调用栈带回 execute() → 编排层打日志 → finally 释放锁 → 事务提交
```

**4.8 为什么判环放在装配期（链与树最本质的区别）**

链和树都要防"绕圈"，但**防护时机完全不同**，这正是一道高频追问：

| | 责任链（本项目） | 规则树（本项目） |
| --- | --- | --- |
| 下一跳由谁决定 | `setNext` 在**启动装配时**决定，不依赖运行时数据 | `next()` 在**运行时**按订单状态决定 |
| 结构性质 | **静态**：装配完成后不可变（`setNext` 只允许一次） | **动态**：每次运行的路径都可能不同 |
| 环从哪来 | 只可能来自"装配写错" | 装配写错 **或** 数据驱动的分支绕回 |
| 判环时机 | **装配期一次**（`Chain.assemble`） | **每轮运行**（`FlowEngine.run` 里的 `visited`） |
| 判环成本 | 启动时一次遍历，运行期为零 | 每次运行一个 `IdentityHashMap` 集合 |
| 额外防护 | 无（链长在装配期就确定了） | 可配置步数上限（兜"合法但病态的长路径"） |
| 报错样例 | `责任链装配成环：a -> b -> a` | `规则树检测到环：OrderLookupNode -> RepostNode -> OrderLookupNode` |

一句话答法：**"链是静态结构，判环在装配期做一次就够，运行期零成本；树是动态结构，环可能来自数据，必须每轮运行都判。"**

判环逻辑本身两个地方是同一套写法（这也是刻意保持一致的）：

```java
Set<X> visited = Collections.newSetFromMap(new IdentityHashMap<X, Boolean>());   // 按对象身份，不是 equals
```
用 `IdentityHashMap` 而不是普通 `HashSet`，是因为节点没有重写 `equals/hashCode`——**按对象身份判断"是不是同一个节点实例"**才是这里要的语义，也避免了将来有人给节点加 `equals` 时判环悄悄失效。

装配期判环由单测守着（`ChainTest`，7 例中的 3 例）：

```java
// ChainTest.java:94-99 —— 自环：setNext 指回自己，assemble 立刻失败
a.setNext(a);
IllegalStateException e = assertThrows(IllegalStateException.class, () -> Chain.assemble(a));
assertTrue(e.getMessage().contains("责任链装配成环"), e.getMessage());
assertTrue(e.getMessage().contains("a -> a"), e.getMessage());

// ChainTest.java:106-111 —— 两节点互指：报错带完整环路 a -> b -> a
a.setNext(b);
b.setNext(a);
assertTrue(e.getMessage().contains("a -> b -> a"), e.getMessage());
```

> 为什么值得写这三个用例？因为**装配期判环的价值就在于"启动即失败"**——如果它自己写错了（比如忘了 `visited.add` 的返回值判断），链路就会静默回到"运行时栈溢出"，所以必须有用例把它钉住。

#### 5. 为什么这么重构（对比表）

| 维度 | 重构前（一个方法里的过程代码） | 重构后（责任链） | 依据 |
|---|---|---|---|
| 可扩展性 | 新增一步校验必须插进 `try` 块中间，改动点在主干 | 新增节点类 + 在 `setNext` 链条里插一个 `.setNext(...)`（`OrderCreateChainConfig.java:26-29`），编排层与既有节点不动 | 见 5.3 的扩展步骤 |
| 可测试性 | 步骤是私有过程代码，测一条分支要起 Spring + Redis + MySQL + MQ | 节点可脱离链路单独构造（依赖全为 `@Resource` 注入字段），执行器语义由 `ChainTest`（7 例）覆盖顺序接力 / 不接力提前结束 / 异常中断 / **装配期判环** / 重复串链拒绝 / 空链拒绝 | `ChainTest.java:42-129` |
| 可读性 | 5 个出口混在 37 行里，全靠 `try/finally` 边界区分"结束"与"回滚" | 顺序在装配处一眼看全；每个类名就是一步的业务名 | `OrderCreateChainConfig.java:19-31` |
| 失败定位 | 只能看堆栈落在第几行；日志与步骤无对应关系 | 异常来自哪个节点类一目了然；节点名即定位坐标 | `UserLockNode.java:27`、`StockDeductNode.java:32` |
| 失败语义 | `return` / `throw` 靠位置区分 | 契约固化：**不接力 = 正常提前结束**（结果枚举带回编排层），`throw` = 中断 + 回滚 | `ChainNode.java:31-52`、`Chain.java:62-65` |
| 结构性防护 | 无（顺序写死在方法里，也谈不上环） | **装配期静态判环** + `setNext` 只允许串一次，写错在启动时就报错 | `Chain.java:43-57`、`ChainNode.java:31-38` |
| 行为等价 | 基准 | 逐条对齐：锁 Key 相同（`RedisConstants.java:19` = `"lock:order:"`）、SQL 相同、事务注解与异常口径相同 | 见下表 |
| 性能 | 基准 | 每次消费多 5 次接口调用 + 1 次 Context 分配（纳秒级），瓶颈仍在 DB 与锁 | `UserLockNode.java:24-25` |

行为等价的逐条对照（面试被问"重构后行为真的没变吗"时按这张表答）：

| 关注点 | 改造前 | 改造后 | 是否等价 |
|---|---|---|---|
| 事务边界 | `@Transactional(rollbackFor = Exception.class)` 在 `createVoucherOrder` | 同一个注解、同一个方法（`VoucherOrderServiceImpl.java:135`） | 等价 |
| 锁 Key | 字面量 `"lock:order:" + userId` | `RedisConstants.LOCK_ORDER_KEY + userId`，常量值就是 `"lock:order:"`（`RedisConstants.java:19`） | 等价 |
| 锁获取失败 | `throw IllegalStateException("用户订单并发锁获取失败")` | 同文本（`UserLockNode.java:26`） | 等价 |
| 扣库存 SQL | `eq(voucherId).gt(stock,0).setSql("stock = stock - 1")` | 同一条（`StockDeductNode.java:24-29`） | 等价 |
| 扣减失败 | `throw IllegalStateException("数据库库存与 Redis 预扣状态不一致")` | 同文本（`StockDeductNode.java:31`） | 等价 |
| 幂等日志文本 | 两条 `log.info` 固定文案 | 同文本（`OrderIdempotentNode.java:24`、`UserVoucherIdempotentNode.java:30-31`） | 等价 |
| 锁释放 | `finally { lock.unlock(); }`（未拿到锁时在 `try` 之外已抛出，到不了这里） | `finally { context.releaseLock(); }`，多了 `userLock != null && isHeldByCurrentThread()` 判断（`OrderCreateContext.java:21`） | 等价，且更稳（防重复释放） |
| 受检异常 | 原样上抛 | `RuntimeException` 原样抛，其余包成 `IllegalStateException("订单创建链执行失败 orderId=…")` | 现有节点只抛运行时异常，实际无差异；包装分支是防御 |

#### 6. 边界（面试追问）

| 追问 | 怎么答 |
|---|---|
| 链上已经有订单 ID 幂等，为什么还要用户+券幂等？ | 两者拦的不是同一件事。`OrderIdempotentNode` 按**订单 ID** 判重，拦的是同一条消息被 MQ 重复投递、或对账用原订单 ID 重投（`RepostNode.java:66-71` 就是复用原 ID）；`UserVoucherIdempotentNode` 按**用户+券**判重，拦的是同一用户同一张券的**另一条**订单消息（不同订单 ID，例如用户重复发起、或取消后重新抢购的场景）。少了第二道，"一人一单"只能靠 Redis 名单，DB 层没有兜底（`hmdp.sql:277` 上 `tb_voucher_order` 只有 `PRIMARY KEY (id)`，没有 `(user_id, voucher_id)` 唯一键）。 |
| 为什么锁放在节点里获取、却放在编排层释放？ | 锁的生命周期必须覆盖整条链而不是单个节点：如果锁在节点里释放，后续节点就跑在锁外了；如果让每个节点自己管锁，异常路径下极易漏释放。所以获取与释放分开——获取是"链的一步"（`UserLockNode`），释放是"链的生命周期管理"（编排层 `finally`）。 |
| 那锁释放会不会早于事务提交？ | 会，这是当前实现的已知窗口。`@Transactional` 在 `createVoucherOrder` 上（`VoucherOrderServiceImpl.java:135`），提交发生在方法**返回之后**，而 `releaseLock()` 在方法内的 `finally`（`:143-145`）执行。理论上第二个同用户消息拿到锁后查库，可能读不到第一条尚未提交的订单。当前正常链路走不到：Lua 预扣阶段已经 `SADD` 过名单，同一用户第二次请求在 Redis 就被拦掉了；彻底关掉这个窗口的做法是把释放挪到事务提交之后（`TransactionSynchronization#afterCompletion`），或给 `(user_id, voucher_id)` 加唯一键在 DB 兜底。（源码中未找到对应实现，待补。） |
| 节点是单例，线程安全吗？ | 安全。节点里没有任何请求级成员变量，依赖全是 `@Resource` 注入的无状态组件（Mapper、`RedissonClient`、`StringRedisTemplate`、`RocketMQTemplate`），一次请求的数据只存在于 `new OrderCreateContext(...)`（`VoucherOrderServiceImpl.java:137`）与只读的 `VoucherOrder` 入参里。 |
| 新增一条风控校验要改哪几处？ | 三处之内：新建 `XxxNode extends ChainNode<VoucherOrder, OrderCreateContext, OrderCreateOutcome>`（`@Service`）；在 `OrderCreateChainConfig.orderCreateChain` 的参数里加形参、在 `setNext` 链条里插入一个 `.setNext(...)`（注意别打乱原有顺序）；不需要改编排层、执行器、已有节点。若风控不通过就**不接力、直接 `return` 一个结果枚举**（视为"本次不下单，正常结束"）；若是系统异常则 `throw`（回滚 + MQ 重试）——这个二选一必须在设计时定清楚，新增结果码就在 `OrderCreateOutcome` 里加一项。 |
| 责任链和规则树怎么选？ | 链是**固定顺序的检验型流水线**：每步都要过，一步不过就断，节点只回答"继续/不继续"。树是**按状态分叉的决策结构**：分支不是顺序而是条件，节点用 `next()` 显式声明下一跳，有统一终点和结果产出。判断标准很简单：如果"下一步是谁"取决于数据状态而不是固定顺序，用树；否则用链。项目里两种都有——订单消费是链（`framework/chain`），对账处置是树（`framework/rule`）。 |
| 为什么不直接上 Sentinel SlotChain / Spring AOP 之类的现有链？ | 需求只是"固定顺序 + 提前结束 + 异常中断 + 顺序集中声明 + 不成环"，`ChainNode` + `Chain` 一共 151 行（64 + 87）、零第三方依赖、可单测，还自带**装配期判环**；引入编排框架或 AOP 会带来注解解析、代理、切面顺序这些额外变量，还要处理 AOP 自调用失效的问题，收益不匹配。真需要熔断限流时再引入对应组件，而不是为了"编排"引入一整套框架。 |
| 节点抛异常，MQ 侧会怎样？ | `createVoucherOrder` 在事务里抛异常 → 事务回滚 → 异常冒泡到 `SeckillOrderListener.onMessage`（`SeckillOrderListener.java:42-52`）→ MQ 按消费重试策略重投同一条消息。因为重试的是**同一条消息（同订单 ID）**，重试时会被节点 1 幂等拦下或继续走完，不会产生重复订单。另外 Redis 预扣已经发生，若重试最终失败，由对账任务兜底处置（对账的处置逻辑见 5.2）。 |
| 链执行完就代表订单一定落库了吗？ | 不代表。接口返回 `Result.ok(orderId)` 的语义是"通过 Redis 预扣并进入 MQ 排队"（`VoucherOrderServiceImpl.java:73-75` 的注释写明），真正落库发生在消费端这条链走完之后。这是"异步削峰"的固有代价，也是必须有 5.2 那套对账的原因。 |

---

### 5.2 规则树：对账处置重构（含判环与步数上限）

> **简历原文（第 5 条后半）**：对账处置重构为规则树（含判环与步数上限）。

**面试话术（四段式：背景 → 剖析 → 构思 → 复盘，可直接背）**

> **① 背景阐述**：秒杀链路的最终一致性兜底是 XXL-JOB 任务 `seckillReconcileJob`（`SeckillReconciliationTask.java:51-52`）。它扫描 Redis 里所有 `seckill:reservation:*` 预扣记录，逐条判断该做什么：订单没落库就重投 MQ、订单已取消就释放预扣、订单已落库就补回一人一单名单、还没到重投窗口就跳过。**一条记录要做一次多分支决策**，这是天然的决策树。
>
> **② 问题剖析**：改造前这棵树是"隐性"的——单条记录的处置逻辑写在 `repairReservations()` 一个方法里，靠 5 个 `if`、3 个 `continue`、2 个 `catch` 拼出层级（全文见仓库里的改造对比文档）。最典型的是那个 `continue`：`if (order == null) { ... continue; }` 之后的"已取消 / 已落库"判断，缩进上看着像在 `order == null` 分支里，实际因为 `continue` 已经跳回循环，它是和 `order == null` **平级**的。读代码的人要在脑子里模拟执行才能还原控制流。另外还有三个后果：新增一种处置动作必须在 75 行嵌套里插代码，回归范围是整个方法；如果订单出现 `status=5`（退款中）这类新状态，会**静默落入 else** 被当作"已落库"去补标记，没有任何显式信号；失败统计散在四处手工置 `hasFailure`，新增分支极易漏标。
>
> **③ 构思实现**：把树从过程代码里"拎出来摆明"。自研一个约 120 行的规则树引擎 `com.hmdp.framework.rule`：`FlowNode` 只有两个方法——`process` 干活、`next` 决定下一跳（返回 `null` 表示终点），分支判断全部写在 `next()` 里；`AbstractFlowNode` 把"先补数据再干活"的节奏定死；`FlowEngine` 用 while 驱动全树。业务侧拆成 9 个节点，整棵树的**唯一岔路口**收敛到 `OrderLookupNode.next()` 三行。两个防护是重点：**判环**按对象身份在一次运行内记录访问过的节点，同一节点重访立即抛异常并打印环路路径（形如 `OrderLookupNode -> RepostNode -> OrderLookupNode`），第一次重访就拦下；**步数上限**可配置（默认 16，对账树显式传 16），只兜底"合法但病态的长路径"。失败语义也统一了：节点只 `throw` 不 `catch`，业务异常冒泡到任务循环按单条失败统计；结果统一收口成 `RepairOutcome` 枚举（`REPOSTED / RELEASED / REPAIRED / SKIPPED / FAILED_MANUAL`），任务从"成功/失败"升级为可分类计数。
>
> **④ 复盘总结**：这点的价值不在性能，而在**让分支显式化 + 让扩展不动主干**。对账是长期在后台跑的兜底逻辑，最怕"顺手改了 else"埋雷；现在改分支先看 `next()`。诚实地说清代价：类数量 +12、调用栈多一层 `FlowEngine`、分支只有两三个的简单场景不该上规则树——本项目这一处有 7 个出口才值得；收益兑现的前提是"分支还会继续长"。

#### 1. 这条在说什么

- 对象：XXL-JOB 任务 `SeckillReconciliationTask` 中"单条预扣记录处置"这一段（`:76-104`），不是整个任务。任务还有另外三件事：数量对账（`:134-158`）、失败语义（`:57-72`）、开关节流（`:48-49`、`:53-55`），本次改造没动。
- 一段输入、一棵树、一个结果：入参是 Redis Key + Hash 字段（`ReservationRequest.java:17-23`），中途数据走 `ReservationContext`（`ReservationContext.java:13-23`），出口是一个 `RepairOutcome` 枚举（`RepairOutcome.java:6-22`）。
- 五个结果值就是任务的统计口径：

| `RepairOutcome` | 含义 | 产出节点 | 任务侧日志 |
|---|---|---|---|
| `SKIPPED` | 未到重投窗口，本轮不处理 | `RepostNode.java:62-65` | `log.debug`（`:127-129`） |
| `REPOSTED` | 订单未落库，已重新投递 MQ | `RepostNode.java:83` | `log.warn`（`:113-116`） |
| `RELEASED` | 订单已取消，已释放 Redis 预扣与库存 | `ReleaseNode.java:54` | `log.info`（`:117-119`） |
| `REPAIRED` | 订单已落库，已补回一人一单标记并清理预扣记录 | `RepairNode.java:33` | `log.info`（`:120-122`） |
| `FAILED_MANUAL` | 自动重投达到上限，转人工，不再自动重试 | `RepostNode.java:53-61` | `log.error`（`:123-126`） |

- 与其它章的分工只在此一句：预扣流水怎么写的、为什么需要它，属于第五章"故障补偿"（历史编号）的内容；这里只讲**拿到流水之后怎么组织处置决策**。`FAILED_MANUAL` 对应的补偿账本属于"补偿任务账本"那条优化，本章只把它当作"新增分支不动主干"的实例引用。

#### 2. 改造前："一棵藏在 continue 里的树"

`git show a66fd1d`（`refactor(reconcile): rebuild seckill reconciliation as rule tree`，2026-09-30）删除的骨架代码，编号①~⑨为流程分支点，与仓库里《对账任务规则树改造对比》HTML 第 2.1 节一致：

```java
// 改造前：SeckillReconciliationTask#repairReservations 的核心片段（a66fd1d 已删除）
while (cursor.hasNext()) {
  try {
    fields = opsForHash().entries(key);
    orderId/userId/voucherId/reservedAt = parseLong(...) 全解析;

    if (任一解析失败) { hasFailure = true; continue; }              // ① 元数据不全
    order = voucherOrderMapper.selectById(orderId);

    if (order == null) {                                            // ② 未落库
      if (!shouldRetry(fields, reservedAt)) continue;               // ③ 未到窗口，静默跳过
      try { syncSend(...); increment(retryCount); put(lastRetryAt); }   // ④ 重投
      catch (Exception e) { hasFailure = true; ... }                //    重投失败
      continue;                                                     // ⑤ ★关键：使 ⑥ 与 ② 平级
    }

    if (Integer.valueOf(4).equals(order.getStatus())) {             // ⑥ 已取消
      released = releaseRedisReservation(...);
      if (released == null) { hasFailure = true; ... }              // ⑦ 释放失败
      else log.info(...);
    } else {                                                        // ⑧ 已落库
      opsForSet().add(orderKey, userId); delete(key);
    }

  } catch (Exception e) { hasFailure = true; ... }                  // ⑨ 单条兜底
}
```

问题清单（逐条都能在真实代码里指出来）：

| # | 问题 | 具体表现 | 后果 |
|---|---|---|---|
| 1 | 分支隐性 | 两个业务决策（是否未落库 / 是否已取消）靠 `continue` 和 `else` 拼出层级 | 读代码要在脑内模拟执行才能还原控制流；⑤ 那个 `continue` 让 ⑥ 与 ② 变成同级，缩进具有欺骗性 |
| 2 | 扩展要动主干 | 新增处置动作（如"重投超 N 次转告警""支付超时但未关单"）必须往 75 行嵌套里插代码 | 回归范围是整方法 |
| 3 | 新状态静默漏处理 | 订单出现 `status=5`（退款中）会默默落入 ⑧ 的 `else`，被当"已落库"补标记 | 没有任何显式信号，错了也不知道 |
| 4 | 失败语义散落 | ①④⑦⑨ 四处手工置 `hasFailure`，逻辑相同、写法各异 | 新增分支极易漏标，失败被静默吞掉 |
| 5 | 可观测性弱 | 只有 `hasFailure` 一个布尔 | "重投了几条、释放了几条"只能翻日志文本 |
| 6 | 难测 | 分支全部内嵌在 `private` 方法里 | 测任意一条分支都要搭 SCAN + Redis + Mapper + MQ 全套 mock |

#### 3. 改造后："同一棵树摆到明面上"

一个输入 → 一个起点 → 三岔路口 → 三个处置节点 → 一个统一终点：

```mermaid
flowchart TD
    T["SeckillReconciliationTask.repairReservations<br/>SCAN seckill:reservation: 星号 逐条处理"] --> R["ReservationRootNode<br/>解析 orderId userId voucherId reservedAt 写入 Context"]
    R -->|"任一字段缺失或非数字"| E1["throw IllegalStateException<br/>任务循环 catch，记 hasFailure"]
    R -->|"四个字段齐全"| L["OrderLookupNode<br/>selectById 查订单写入 Context"]
    L --> N{"OrderLookupNode.next<br/>整棵树唯一的分支声明处"}
    N -->|"order 为 null"| P["RepostNode<br/>超上限 FAILED_MANUAL / 未到窗口 SKIPPED / 到窗口 syncSend 重投 REPOSTED"]
    N -->|"status 等于 4"| S["ReleaseNode<br/>执行 seckill_rollback.lua 释放预扣 RELEASED"]
    N -->|"其他状态"| F["RepairNode<br/>SADD 名单加 DEL 预扣记录 REPAIRED"]
    P --> E["ReconcileEndNode<br/>next 返回 null，返回 context.outcome"]
    S --> E
    F --> E
    E --> O["任务 logOutcome 按枚举分类打日志"]
    P -->|"syncSend 抛异常"| E2["throw 任务循环 catch，记 hasFailure"]
```

节点职责表：

| # | 节点 | 输入（从哪来） | 动作（真实代码） | 出口 |
|---|---|---|---|---|
| 1 | `ReservationRootNode` | `ReservationRequest` 的 Hash 字段 | 解析 4 个字段写入 Context；不完整时 `throw`（`ReservationRootNode.java:21-29`） | `next()` 固定返回 `orderLookupNode`（`:34-37`） |
| 2 | `OrderLookupNode` | Context 中的 `orderId` | `selectById` 查订单写入 Context（`:29-31`） | `next()` 三行分支：`order == null` → Repost / `status == 4` → Release / 其他 → Repair（`:37-43`） |
| 3 | `RepostNode` | Context + `retryCount`、`lastRetryAt` | 超上限 → 账本标记失败 + `FAILED_MANUAL`；未到窗口 → `SKIPPED`；到窗口 → `syncSend` 原订单 ID + `increment(retryCount)` + `put(lastRetryAt)` + 账本记账 → `REPOSTED`（`:51-85`） | `next()` 固定返回 `reconcileEndNode`（`:102-105`） |
| 4 | `ReleaseNode` | Context 中的 `voucherId`、`userId` | 执行 `seckill_rollback.lua`（3 个 Key + userId）→ 结果为 `null` 时 `throw`；成功则账本闭环 → `RELEASED`（`:40-55`） | `next()` 固定返回 `reconcileEndNode`（`:59-62`） |
| 5 | `RepairNode` | Context + `reservationKey` | `SADD seckill:order:{voucherId} userId` + `DEL` 预扣记录 + 账本闭环 → `REPAIRED`（`:27-34`） | `next()` 固定返回 `reconcileEndNode`（`:38-41`） |
| 6 | `ReconcileEndNode` | Context 中的 `outcome` | 直接返回 `context.getOutcome()`（`:14-16`） | `next()` 返回 `null`，树到终点（`:19-22`） |

任务主流程收缩成"扫描 + 驱动 + 统计"，改造后只剩这些：

```java
// SeckillReconciliationTask.java:83-98
while (cursor.hasNext()) {
    String reservationKey = cursor.next();
    try {
        Map<Object, Object> fields = stringRedisTemplate.opsForHash().entries(reservationKey);   // :86
        ReservationContext context = new ReservationContext();                                   // :87
        RepairOutcome outcome = FlowEngine.run(                                                  // :88
                reservationRootNode,
                new ReservationRequest(reservationKey, fields),
                context,
                RECONCILE_MAX_STEPS);                                                            // :92
        logOutcome(reservationKey, outcome, context);                                            // :93
    } catch (Exception e) {
        hasFailure = true;                                                                       // :95 失败口径与改造前一致
        log.error("处理秒杀预扣记录失败 reservationKey={}", reservationKey, e);                    // :96
    }
}
```

五条铁律（照搬模板时必须守住）：

1. 节点单例，一次请求的数据绝不放成员变量，全部走 Context（`ReservationContext.java:8-9` 的注释就写着这条）；
2. 节点里只 `throw` 不 `catch`，业务异常统一冒泡到任务循环；
3. 装配只做一次（启动期 `@Resource` 注入，节点之间靠 `next()` 指向）；
4. 一次请求一个 Context，在循环里 `new`（`SeckillReconciliationTask.java:87`）；
5. 引擎按访问标记判环，并用可配置步数上限兜底路径爆炸。

#### 4. 引擎核心代码：三个类，可整包搬走

> 读法：**4.1~4.3 是引擎**（三个类型、共 131 行，去掉业务后原样可搬到别的项目）；**4.4 调用**；**4.5 执行链路**。
> 业务侧节点（根节点、岔路口、三个分支、收口）在第 7 节。

**4.1 节点契约**（`framework/rule/FlowNode.java` 全文，22 行）

```java
public interface FlowNode<T, C, R> {

    /** 本节点业务：查数据、拦异常、写上下文。 */
    R process(T request, C context) throws Exception;

    /** 下一跳：返回 null 表示到达终点。分支判断写在这里。 */
    FlowNode<T, C, R> next(T request, C context) throws Exception;

    /** 节点名称：用于判环与超限异常中的路径展示，默认取类名，可重写。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
```

`next()` 返回的是**对象引用**——这既是"分支显式化"的来源（分支不再是藏起来的 `continue`），也是**必须判环**的原因：编译器管不了对象引用指回自己。

**4.2 抽象节点：把"先补数据、再干活"的节奏定死**（`framework/rule/AbstractFlowNode.java` 全文，20 行）

```java
public abstract class AbstractFlowNode<T, C, R> implements FlowNode<T, C, R> {

    @Override
    public R process(T request, C context) throws Exception {
        loadData(request, context);         // 钩子：默认空；需要并行预加载的节点才重写
        return handle(request, context);    // 子类只写这一块
    }

    /** 钩子：默认空；需要并行预加载数据的节点才重写。 */
    protected void loadData(T request, C context) throws Exception {
    }

    /** 本节点业务：子类必填。 */
    protected abstract R handle(T request, C context) throws Exception;
}
```

本项目的对账节点都没有重写 `loadData`（查库都写在 `handle` 里），但钩子留着——将来某个节点要并行预加载（比如同时查订单和券）时，不用改引擎、也不用改其他节点。

**4.3 引擎：`while` 驱动 + 两层防护**（`framework/rule/FlowEngine.java:22-60`，报错路径拼接见 `:63-88`）

```java
/** 默认步数上限：仅作路径爆炸的兜底，正常树的最长路径远小于该值 */
public static final int DEFAULT_MAX_STEPS = 16;

/** 使用默认步数上限运行规则树 */
public static <T, C, R> R run(FlowNode<T, C, R> start, T request, C context) throws Exception {
    return run(start, request, context, DEFAULT_MAX_STEPS);
}

public static <T, C, R> R run(FlowNode<T, C, R> start, T request, C context, int maxSteps) throws Exception {
    if (maxSteps <= 0) {
        throw new IllegalArgumentException("maxSteps 必须为正数：" + maxSteps);       // ⓪ 配置写错就地挡掉
    }
    Set<FlowNode<T, C, R>> visited = Collections.newSetFromMap(
            new IdentityHashMap<FlowNode<T, C, R>, Boolean>());                      // 按"对象身份"记录
    List<FlowNode<T, C, R>> path = new ArrayList<>();                                // 记录路径供报错

    FlowNode<T, C, R> node = start;
    R result = null;
    while (node != null) {
        if (!visited.add(node)) {                                                    // ① 判环
            throw new IllegalStateException("规则树检测到环：" + loopPath(path, node));
        }
        if (path.size() >= maxSteps) {                                               // ② 步数上限
            throw new IllegalStateException("规则树超过步数上限（" + maxSteps + " 步）：" + pathNames(path));
        }
        path.add(node);
        result = node.process(request, context);      // 本节点干活
        node = node.next(request, context);           // 决定下一跳（分支在这里）
    }
    return result;                                     // 终点产出的结果
}
```

报错信息不能只说"出错了"——两个辅助方法负责把**路径**拼出来：

```java
/** 从重复节点在路径中上一次出现的位置开始拼接环路，例如 a -> b -> a */
private static <T, C, R> String loopPath(List<FlowNode<T, C, R>> path, FlowNode<T, C, R> repeat) {
    int from = 0;
    for (int i = 0; i < path.size(); i++) {
        if (path.get(i) == repeat) { from = i; break; }
    }
    StringBuilder sb = new StringBuilder();
    for (int i = from; i < path.size(); i++) {
        sb.append(path.get(i).name()).append(" -> ");
    }
    return sb.append(repeat.name()).toString();
}

/** 拼接已访问的节点路径，用于超限时的诊断信息 */
private static <T, C, R> String pathNames(List<FlowNode<T, C, R>> path) { /* a -> b -> c ... */ }
```

这也是 `FlowNode.name()` 存在的唯一理由：异常信息里要能直接读出"环在哪"（默认取类名，业务节点不用重写；单测里的假节点才重写成短名）。两层防护的分工与设计意图见第 5 节。

**4.4 调用：任务只负责扫描、驱动、统计**（`listener/SeckillReconciliationTask.java`）

```java
private static final int RECONCILE_MAX_STEPS = 16;                     // :39 这棵树显式声明自己的上限

// 单条记录：从根节点跑完整棵树，拿回处置结果
RepairOutcome outcome = FlowEngine.run(
        reservationRootNode,
        request,
        context,
        RECONCILE_MAX_STEPS);                                          // :88-92
```

任务本身不做任何分支判断——它只做三件事：`SCAN` 拉出待对账记录、把每条记录交给引擎、按返回的 `RepairOutcome` 分类计数。

**4.5 执行链路（对照代码看）**

```text
SeckillReconciliationTask.repairReservations()
  → SCAN seckill:reservation:*  → 逐条构造 ReservationRequest（只读快照）
    → FlowEngine.run(reservationRootNode, request, new ReservationContext(), 16)
        → ReservationRootNode.process  校验元数据；不完整 → throw（按"单条失败"统计）
          next() = OrderLookupNode
        → OrderLookupNode.process      selectById 加载订单进上下文
          next() = 订单为空 ? RepostNode : (status==4 ? ReleaseNode : RepairNode)   ★ 唯一岔路口
        → RepostNode / ReleaseNode / RepairNode   各干一件事，并把 outcome 写进上下文
          next() = ReconcileEndNode
        → ReconcileEndNode.process     return context.getOutcome()
          next() = null                 ★ 终点，引擎返回 result
  → 任务按 outcome 分类计数（REPOSTED / RELEASED / REPAIRED / SKIPPED / FAILED_MANUAL）
```

整个链路里**只有一处 `if`**（`OrderLookupNode.next()` 的三行分支），其余节点都是"固定下一跳"。这就是"同一棵树摆到明面上"的实际含义。

#### 5. 判环与步数上限为什么必须有

**先说为什么"必须防"**。规则树是"节点自己决定下一跳"，链路的正确性**没有任何编译期保证**——`next()` 返回的是对象引用，写错就是一个环。环的两个来源都很现实：

1. 装配错误：A 的 `next()` 指向 B、B 的 `next()` 又指回 A；或某个节点在某个分支上返回了自己；
2. 分支条件写反：数据驱动的分支（比如 `OrderLookupNode.next()` 里判断取反）把流程送回上游节点。

**再说改造前的实现为什么不够**。改造前引擎里只有一个硬编码常量（`69d0e4b` 之前的 `FlowEngine`）：

```java
// 改造前（69d0e4b 已替换）：只有硬编码步数上限
private static final int MAX_STEPS = 100;
...
while (node != null) {
    if (++step > MAX_STEPS) {
        throw new IllegalStateException("规则树疑似成环（超过 " + MAX_STEPS + " 步）");
    }
    ...
}
```

三个问题：① 要空转 100 步才报错，报错只有一句话，**没有路径信息**，不知道环在哪；② 上限是常量，改不了——对账树最长路径只有 4 个节点，16 步就够，但将来另一棵合法的长链树超过 100 步就没法跑；③ "疑似成环"把两类不同的问题（真环 / 合法但病态的长路径）混在一起报，定位时无法区分。

**改造后的实现**（`69d0e4b` `refactor(rule): detect cycles by node revisit and make step limit configurable`，`FlowEngine.java:39-60`）分两层：

| 防护 | 拦什么 | 怎么判定 | 什么时候报 | 报什么 | 可配置性 |
|---|---|---|---|---|---|
| 判环 | 错误的装配 / 数据驱动的回路（真环） | `Collections.newSetFromMap(new IdentityHashMap<>())`，按**对象身份**记录本次运行访问过的节点，`visited.add(node)` 返回 `false` 即重访（`FlowEngine.java:43-44`、`:49-51`） | **第一次重访**就报，不等走满 | `IllegalStateException("规则树检测到环：" + 环路路径)`，例如 `OrderLookupNode -> RepostNode -> OrderLookupNode`（`loopPath` `:63-76`，节点名来自 `FlowNode.name()` `FlowNode.java:19-21`） | 与上限无关，永远开启 |
| 步数上限 | "合法但病态的长路径"（路径爆炸） | 已处理节点数 `path.size()` 达到上限（`:52-54`） | 走到上限时 | `IllegalStateException("规则树超过步数上限（N 步）：" + 已走路径)`（`pathNames` `:79-88`） | 可配置：`DEFAULT_MAX_STEPS = 16`（`:23`），重载 `run(..., maxSteps)`（`:39`）；对账树显式传 `RECONCILE_MAX_STEPS = 16`（`SeckillReconciliationTask.java:39`、`:88-92`）；非正数在入口抛 `IllegalArgumentException`（`:40-42`），把"配置写错"就地挡掉 |

```mermaid
flowchart TD
    S["FlowEngine.run 初始化<br/>visited 空集合按对象身份<br/>path 空列表 node 等于 start"] --> A{"node 是否为 null"}
    A -->|"是"| Z["返回 result，本次运行结束"]
    A -->|"否"| B{"visited.add node 是否成功"}
    B -->|"失败，该节点本次运行已访问过"| C["throw IllegalStateException<br/>规则树检测到环 加 环路路径"]
    B -->|"成功"| D{"path.size 大于等于 maxSteps"}
    D -->|"是"| F["throw IllegalStateException<br/>规则树超过步数上限 加 已走路径"]
    D -->|"否"| G["path.add node<br/>result 等于 node.process<br/>node 等于 node.next"]
    G --> A
```

三个容易被追问的实现细节：

1. **为什么先判环、后判步数**？同一次迭代里判环在前（`:49-54`）。这样只要 `maxSteps` 不小于环的周长，真环一定以"检测到环 + 环路路径"的形式报出来，而不会被上限掩盖成"超过步数上限"。默认值取 16（而不是 1~2）也是这个考虑：留足冗余，上限只兜底病态路径。
2. **为什么用 `IdentityHashMap` 而不是普通 `HashSet`**？判环关心的是"同一个节点实例"，用对象身份比较可以完全绕开 `equals/hashCode` 的实现问题——节点是 Spring 单例、没有重写 `equals`，但把语义写死在数据结构里，比依赖"恰好没重写 equals"更稳。
3. **为什么节点要提供 `name()`**？报错信息要能直接读出环在哪。默认取类名（`FlowNode.java:19-21`），业务节点不用重写；单测里的假节点重写成短名，断言里就能直接匹配 `"a -> b -> a"`（`FlowEngineTest.java:96`、`:109`）。

#### 6. 行为零变化的证明（真值表 / 对照）

真值表逐条对齐改造前后的走向（输入状态 → 原路径 → 新节点 → 结果）。这张表是**改造当时的**等价性证据：

| 输入状态 | 改造前走向 | 改造后走向 | 结果 |
|---|---|---|---|
| 元数据缺字段 / 非数字 | ① `if` → `hasFailure = true` + `continue` | `ReservationRootNode` `throw` → 循环 `catch`（`SeckillReconciliationTask.java:94-97`） | 单条失败，口径一致 |
| 订单未落库 + 未到重投窗口 | ③ `if` → `continue`（静默） | `RepostNode` → `SKIPPED`（`RepostNode.java:62-65`） | 跳过，不标失败 |
| 订单未落库 + 到窗口 + MQ 成功 | ④ `syncSend` + 记 `retryCount` / `lastRetryAt` | `RepostNode` → `REPOSTED`（`:70-84`） | 重投成功 |
| 订单未落库 + 到窗口 + MQ 失败 | ④ `catch` → `hasFailure` | `RepostNode` `throw` → 循环 `catch`（`RepostNode.java:72-74`） | 单条失败 |
| 订单已落库 + `status=4` + 释放成功 | ⑥⑦ `released != null` → `log.info` | `ReleaseNode` → `RELEASED`（`ReleaseNode.java:40-55`） | 释放成功 |
| 订单已落库 + `status=4` + 释放为 `null` | ⑦ `if` → `hasFailure` | `ReleaseNode` `throw` → 循环 `catch`（`ReleaseNode.java:49-51`） | 单条失败 |
| 订单已落库 + 其它状态 | ⑧ `else` → `SADD` + `DEL` | `RepairNode` → `REPAIRED`（`RepairNode.java:27-34`） | 修复成功 |
| 任意步骤抛异常 | ⑨ 外层 `catch` → `hasFailure` | 任务循环 `catch`（位置不变，`SeckillReconciliationTask.java:94-97`） | 单条失败 |

**必须诚实标注的一处例外**（面试被追问"真的零变化吗"时主动说出来，反而加分）：

| 项 | 改造前 | 改造后 | 结论 |
|---|---|---|---|
| 释放预扣用的脚本 | `ClassPathResource("seckill_release.lua")` | `ClassPathResource("seckill_rollback.lua")`（`ReleaseNode.java:23-29`） | **这一处确实是行为变化，而且是修复**：`seckill_release.lua` 在 2026-09-22 的 `3d3d206`（`fix(seckill): unify rollback script and allow repurchase after cancellation`）里已被删除、合并进 `seckill_rollback.lua`，但对账任务仍写着旧文件名——也就是说改造前这条"已取消 → 释放预扣"的分支在运行时是坏的（脚本资源不存在）；两个脚本逐行逻辑一致（`sismember` 命中才 `srem` + `incrby` + `del`，未命中只 `del`，见 `seckill_rollback.lua:5-12`），差别只在注释。改造顺带修掉了这个断链。 |
| 日志细节 | `log.info("已取消订单完成 Redis 预扣修复 orderId={}, released={}", ...)`；跳过路径不打日志 | `log.info("已取消订单完成 Redis 预扣修复 orderId={}", ...)`（少一个 `released` 参数）；跳过路径多一条 `log.debug`（`SeckillReconciliationTask.java:127-129`） | 不影响业务与失败口径 |

Redis Key 拼接、MQ 发送参数（`orderTopic` + 3000ms 超时）、重试窗口条件（`staleSeconds=30` / `retryIntervalSeconds=60`，`RepostNode.java:39-44`、`:92-99`）、失败计数与 XXL-JOB 抛异常口径，全部逐字保留（任务侧仍是"单条 `catch` 累计 `hasFailure`，最后抛异常给调度中心"，`SeckillReconciliationTask.java:57-72`、`:94-97`）。

另外说明一点时间顺序：真值表证明的是 `a66fd1d` 那一次改造**当时**的等价性。之后 `990d72f`（补偿任务账本）在 `RepostNode` / `ReleaseNode` / `RepairNode` 里增加了账本写入，并新增了 `FAILED_MANUAL` 结果——那是**功能新增**，不是等价改造（见 5.3）。

#### 7. 节点侧代码：一棵树只有一处岔路口

> 口诀：**根节点校验 → 岔路口查库并决定去哪 → 分支节点各干一件事 → 收口节点产出结果**。
> 引擎侧代码在第 4 节；这一节只看业务节点，每个节点都短到能一眼看完。

**7.1 上下文：节点之间唯一的通信方式**

```java
/** 单条预扣记录的原始输入：Redis Key + Hash 字段，只读。 */
public class ReservationRequest {
    public static final String ORDER_ID_FIELD   = "orderId";
    public static final String USER_ID_FIELD    = "userId";
    public static final String VOUCHER_ID_FIELD = "voucherId";
    public static final String RESERVED_AT_FIELD = "reservedAt";
    public static final String RETRY_COUNT_FIELD = "retryCount";
    public static final String LAST_RETRY_AT_FIELD = "lastRetryAt";
    private final String reservationKey;
    private final Map<Object, Object> fields;      // 不可变：只有读方法
}
```

```java
/**
 * 一次对账处理过程中的上下文：规则树节点写，后面的节点与任务读。
 * 铁律：节点是单例，任何"一次请求的数据"都必须放在这里，不能放节点成员变量。
 */
@Data
public class ReservationContext {

    /** 解析后的预扣元数据（ReservationRootNode 写入）。 */
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private Long reservedAt;

    /** 数据库订单（OrderLookupNode 写入）。 */
    private VoucherOrder order;

    /** 最终处置结果（各处置节点写入，ReconcileEndNode 返回）。 */
    private RepairOutcome outcome;
}
```

> 这条"铁律"不是我总结的，是**源码注释里原话**——正因为节点是单例，任何请求级数据放进成员变量都会串号。

**7.2 根节点：只做校验，然后固定下一跳**（`ReservationRootNode.java` 全文 38 行；校验在 `:20-31`，下一跳在 `:33-37`）

```java
@Service
public class ReservationRootNode extends AbstractFlowNode<ReservationRequest, ReservationContext, RepairOutcome> {

    @Resource private OrderLookupNode orderLookupNode;          // 下一个是谁：注入进来

    @Override
    protected RepairOutcome handle(ReservationRequest request, ReservationContext context) {
        context.setOrderId(request.longField(ReservationRequest.ORDER_ID_FIELD));      // 写上下文
        context.setUserId(request.longField(ReservationRequest.USER_ID_FIELD));
        context.setVoucherId(request.longField(ReservationRequest.VOUCHER_ID_FIELD));
        context.setReservedAt(request.longField(ReservationRequest.RESERVED_AT_FIELD));
        if (context.getOrderId() == null || context.getUserId() == null
                || context.getVoucherId() == null || context.getReservedAt() == null) {
            throw new IllegalStateException("秒杀预扣元数据不完整 reservationKey=" + request.getReservationKey()
                    + ", fields=" + request.getFields());                              // 拦 → 链断
        }
        return null;                                                                   // 中间节点不产出结果
    }

    @Override
    public FlowNode<ReservationRequest, ReservationContext, RepairOutcome> next(
            ReservationRequest request, ReservationContext context) {
        return orderLookupNode;                                                        // 固定下一跳
    }
}
```

**7.3 岔路口：整棵树唯一的 `if`**（`OrderLookupNode.java` 全文 45 行）

```java
/** 查库节点：加载订单进上下文，并按订单状态决定下一跳。三个分支集中在这里声明，就是整棵树的"岔路口"。 */
@Service
public class OrderLookupNode extends AbstractFlowNode<ReservationRequest, ReservationContext, RepairOutcome> {

    @Resource private VoucherOrderMapper voucherOrderMapper;
    @Resource private RepostNode repostNode;
    @Resource private ReleaseNode releaseNode;
    @Resource private RepairNode repairNode;

    @Override
    protected RepairOutcome handle(ReservationRequest request, ReservationContext context) {
        VoucherOrder order = voucherOrderMapper.selectById(context.getOrderId());      // 查
        context.setOrder(order);                                                      // 写上下文
        return null;
    }

    @Override
    public FlowNode<ReservationRequest, ReservationContext, RepairOutcome> next(
            ReservationRequest request, ReservationContext context) {
        if (context.getOrder() == null) {
            return repostNode;                          // 未落库 → 重投分支
        }
        if (Integer.valueOf(4).equals(context.getOrder().getStatus())) {
            return releaseNode;                         // 已取消 → 释放分支
        }
        return repairNode;                              // 已落库（含未来新增的一切状态）→ 补标记分支
    }
}
```

> **这三行就是"藏在 `continue` 里的树"变成"摆到明面上的树"的全部内容**。改造前这三个判断散在任务的 `for` 循环里，每一条都对应一个 `continue`；现在它们只出现在 `next()` 里，读代码的人一眼能看全分支。

**7.4 分支节点：一个节点一种处置**（`RepostNode.java` 摘录，共 106 行）

```java
@Override
protected RepairOutcome handle(ReservationRequest request, ReservationContext context) {
    long retryCount = currentRetryCount(request);                       // 读 Redis 里的重投次数
    if (retryCount >= maxRetryCount) {
        // 达到人工介入阈值：停止自动重投，账本标记终态失败
        taskCompensationRecorder.markFailed(TaskCompensation.BIZ_TYPE_ORDER_CREATE, context.getOrderId(),
                "自动重投超过上限 " + maxRetryCount + " 次");
        log.warn("秒杀订单补偿超过最大重试次数，转人工处理 orderId={}, retryCount={}",
                context.getOrderId(), retryCount);
        context.setOutcome(RepairOutcome.FAILED_MANUAL);                // 写上下文：结果分类
        return null;
    }
    if (!shouldRetry(request, context.getReservedAt())) {
        context.setOutcome(RepairOutcome.SKIPPED);                      // 没到重投窗口 → 静默跳过
        return null;
    }
    VoucherOrder retryOrder = new VoucherOrder()
            .setId(context.getOrderId())                                // ★ 复用原订单 ID，不生成新单
            .setUserId(context.getUserId())
            .setVoucherId(context.getVoucherId());
    try {
        rocketMQTemplate.syncSend(orderTopic, JSONUtil.toJsonStr(retryOrder), 3000);
    } catch (Exception e) {
        throw new IllegalStateException("秒杀订单自动补偿重投失败 reservationKey=" + request.getReservationKey(), e);
    }
    // 重投成功后才记账：Redis（次数 +1、时间戳）+ DB 账本
    context.setOutcome(RepairOutcome.REPOSTED);
    return null;
}

@Override
public FlowNode<ReservationRequest, ReservationContext, RepairOutcome> next(
        ReservationRequest request, ReservationContext context) {
    return reconcileEndNode;                                            // 三条分支都汇到同一个终点
}
```

另外两个分支节点同样只干一件事：`ReleaseNode`（订单已取消 → 调共用回滚脚本幂等释放预扣，脚本返回 `null` 先抛异常，释放成功才把账本闭环为成功）、`RepairNode`（订单已落库 → `SADD` 补回一人一单名单 + 删预扣记录，再把账本闭环）。

**7.5 收口节点：终点只做"把结果交出去"**（`ReconcileEndNode.java` 全文 23 行）

```java
/** 终点节点：统一收口，返回本次处置结果；next 返回 null 表示链到头。 */
@Service
public class ReconcileEndNode extends AbstractFlowNode<ReservationRequest, ReservationContext, RepairOutcome> {

    @Override
    protected RepairOutcome handle(ReservationRequest request, ReservationContext context) {
        return context.getOutcome();          // 终点产出：引擎把它作为 run() 的返回值
    }

    @Override
    public FlowNode<ReservationRequest, ReservationContext, RepairOutcome> next(
            ReservationRequest request, ReservationContext context) {
        return null;                          // null = 链到头
    }
}
```

**7.6 三条分支的失败语义（一张表看完）**

| 分支 | 触发条件 | 节点做什么 | 产出 outcome | 失败时 |
| --- | --- | --- | --- | --- |
| 重投 | 订单查不到（未落库） | 到窗口才 `syncSend` 原订单 ID；未到窗口直接跳过；超上限转人工 | `REPOSTED` / `SKIPPED` / `FAILED_MANUAL` | `syncSend` 抛异常 → 冒泡到任务按"单条失败"统计，本轮任务失败并由 XXL-JOB 重试 |
| 释放 | 订单存在且 `status = 4`（已取消） | 调共用回滚脚本释放预扣 | `RELEASED` | 脚本返回 `null`（资格已不在）→ 先抛异常，**不会**误记成功 |
| 补标记 | 订单存在且 `status ≠ 4`（已落库） | `SADD` 补回一人一单名单 + 删预扣记录 | `REPAIRED` | Redis 操作异常 → 冒泡，同上 |

**节点只 `throw`、不 `catch`**：异常统一冒泡到任务循环，由它按"单条失败"计数并决定整轮是否失败——这也是"引擎不掺业务、业务不管调度"的分工。

**7.7 两个容易被忽略的设计点**

**(1) 三个分支节点的 `next()` 都指向同一个终点**：`RepostNode.java:101-105`、`ReleaseNode.java:59-62`、`RepairNode.java` 的 `next()` 全部 `return reconcileEndNode`——除了 `OrderLookupNode` 那一处岔路，全树再无分支。另外注意 `return repairNode` 是**兜底分支**：将来新增订单状态（退款中、已退款…）会自动落到"补标记"，不用改这棵树的骨架。带来的实际好处是**结果只有一处产出**（`ReconcileEndNode.handle` 返回 `context.getOutcome()`），任务侧只按枚举分类计数，不用在每个节点里各自记日志。

**(2) "跳过"和"失败"是两件事**——`RepostNode` 内部有四个出口，语义必须分清：

| 出口 | 条件 | outcome | 算不算本轮失败 |
| --- | --- | --- | --- |
| 正常跳过 | 还没到窗口（`stale-seconds` 默认 30 秒未到，或距上次重投不足 `retry-interval-seconds` 默认 60 秒） | `SKIPPED` | 不算 |
| 正常重投 | 到窗口且 `syncSend` 成功 | `REPOSTED` | 不算 |
| 转人工 | 重投次数达到上限（默认 5 次） | `FAILED_MANUAL` | **不算**——否则"转人工"会被每分钟一次的任务无限重试 |
| 真失败 | `syncSend` 抛异常 | 异常冒泡 | **算**：本轮任务失败 → XXL-JOB 重试 |

把 `SKIPPED` 当成失败，会让每分钟一轮的对账任务永远处于"失败重试"状态，重试机制也就失去意义——这是这类补偿任务最容易踩的坑。

#### 8. 边界与面试追问

| 追问 | 怎么答 |
|---|---|
| 为什么这里用规则树，不用责任链？ | 因为处置动作是**按订单状态分叉**的，不是顺序闯关：未落库去重投、已取消去释放、已落库去补标记，三选一，不存在"全过一遍"的顺序。责任链适合"每步都要过、一步不过就断"的检验场景（订单消费那条链就是），用链表达不了分叉——硬用链就得在节点里写 `if` 决定跳过谁，分支又回到隐性状态。 |
| 引擎会不会死循环？ | 不会。判环按"同一次运行内节点被重复访问"识别，`visited.add(node)` 返回 `false` 立即 `throw`，第一次重访就拦下，并打印环路（如 `OrderLookupNode -> RepostNode -> OrderLookupNode`，`FlowEngine.java:49-51`、`:63-76`）。所以即使 `next()` 装配错了，任务也只是单条失败，不会把线程挂死。 |
| 既然有判环，为什么还要步数上限？ | 两者拦的不是一类问题。判环拦"回到走过的节点"，拦不住**合法但病态的路径爆炸**（比如分支不断走向新节点、永远到不了终点，但每个节点只访问一次）；上限就是给这种情况兜底。另外上限可配置，是因为"多长算病态"因树而异：对账树最长路径 4 个节点，显式传 16 就足够（`SeckillReconciliationTask.java:39`），把它留在引擎里硬编码成 100 反而既拦不住病态、又限制将来合法的长链树。 |
| 步数上限配成 0 或负数会怎样？ | 入口直接 `throw new IllegalArgumentException("maxSteps 必须为正数：0")`（`FlowEngine.java:40-42`），不会静默变成"不限制"或"立刻失败"。这是把配置写错挡在启动/调用边界上，单测也覆盖了这条（`FlowEngineTest.java:135-141`）。 |
| 节点是单例，线程安全吗？ | 安全。节点不持有任何请求级状态：`ReservationContext` 在循环里 `new`（`SeckillReconciliationTask.java:87`），`ReservationRequest` 只读（`ReservationRequest.java:8-23`），节点依赖全是 `@Resource` 注入的单例组件。`ReservationContext.java:8-9` 的类注释把这条写成了铁律。 |
| 迁移到"节点重访判环"之后，"合法长路径"会不会反而跑不动？ | 不会，只要 `maxSteps` 配置得当。判环只在**同一个节点实例被再次访问**时才触发，纯线性长链（a→b→c→…→z）每个节点只访问一次，不触发判环；只有超过 `maxSteps` 才报超限，此时报错会带上完整已走路径（`FlowEngine.java:53`、`:79-88`），把"上限该调大"还是"装配错了"区分开。 |
| 怎么保证改造后行为不变？ | 先列真值表（输入状态 → 原路径 → 新节点）逐条对齐；Redis Key 拼接、回滚脚本逻辑、MQ 参数、重试窗口、失败计数与 XXL-JOB 抛异常口径全部保留；失败统计仍是"单条 `catch` 累计 `hasFailure`，最后抛异常给调度"。唯一一处真实差异是释放分支原先引用的 `seckill_release.lua` 已被 `3d3d206` 删除，改造顺带修正为 `seckill_rollback.lua`（逐行逻辑一致），这一点我会主动说明。 |
| 为什么不引入状态机或工作流框架（Spring StateMachine / Flowable）？ | 当前只有一条主链路加三个处置分支，工作流框架的配置、持久化、运维成本远高于收益：要引入引擎和定义文件、要处理版本与热部署，还得养一套管理台。规则树用普通 Java 类表达、节点就是 Spring Bean，能看懂、能断点、能单测；`framework/rule` 与业务解耦，将来别的多分支流水线可以整包搬走。选型标准是"分支会不会继续长、要不要跨团队编排"，本场景两者都不成立。 |
| 新增一种处置动作要改哪里？ | 加一个节点类 + 在 `OrderLookupNode.next()` 加一行分支（或在既有节点内部加一个出口）。扫描循环、`FlowEngine.run` 调用、引擎本身都不用动。真实先例：`990d72f` 增加"重投超限转人工"，只改了 `RepostNode.handle`（开头加一段上限判断）、`RepairOutcome` 加一个枚举值、任务侧 `logOutcome` 的 `switch` 加一个 `case`（4 行），`OrderLookupNode.next()` 的三行分支和主流程一行没动。 |
| 节点里 `throw` 出去谁 `catch`？失败口径和改造前一致吗？ | 由任务循环的 `catch`（`SeckillReconciliationTask.java:94-97`）接住，置 `hasFailure = true` 并记 error 日志；`repairReservations` 返回后，`reconcile()` 里若有失败就 `throw IllegalStateException("秒杀对账存在未完成的自动补偿")`，让 XXL-JOB 把本次执行记为失败并按配置重试（`:57-72`）。口径与改造前完全一致——改造前是在方法体内四处手工置 `hasFailure`，现在收敛成一处。 |
| 如果某个节点忘了写 `outcome` 就返回 `null`，会怎样？ | 终点返回 `context.getOutcome()`，即 `null`；任务侧 `logOutcome` 对 `null` 有显式分支，打 `log.warn("秒杀预扣记录未产出处置结果 reservationKey={}")`（`SeckillReconciliationTask.java:107-111`）。这是"最坏情况不会被静默吞掉"的兜底：不分类、不统计，但一定留痕。 |
| 规则树会不会让每条记录的处理变慢？ | 每条记录多的是 while 引擎循环 + 一个 Context 分配 + 若干次 `next()` 调用，都是纳秒级；真正的开销在 `SCAN`、`selectById` 查库、Redis 脚本和 MQ 发送上。所以这个改造不是性能优化，不能用压测数据去证明收益——收益是扩展成本、可观测性和"分支显式化"，代价是 +12 个类和更深的调用栈（`FlowEngine.run` → `node.process` → `handle`，单步调试略绕）。 |

---

### 5.3 可测试性：新增分支不改主流程、节点可独立单测

> **简历原文（第 5 条结论）**：新增分支无需改动主流程且节点可独立单测。

#### 1. "不改主流程"在两边各指什么

| 扩展动作 | 必须动的（只有这些） | 不用动的 | 依据 |
|---|---|---|---|
| 责任链加一步校验（例：风控、限购） | 新建 `XxxNode extends ChainNode<VoucherOrder, OrderCreateContext, OrderCreateOutcome>`（`@Service`）；在 `OrderCreateChainConfig.orderCreateChain` 加一个形参、在 `setNext` 链条里插入一个 `.setNext(...)`（若要新增"跳过原因"，在 `OrderCreateOutcome` 里加一项枚举） | `Chain` / `ChainNode`（框架零改动）；`VoucherOrderServiceImpl.createVoucherOrder`（编排层，事务、Context、锁释放都不动）；其它 5 个节点 | `OrderCreateChainConfig.java:19-31` |
| 规则树加一个处置分支 | 新建 `XxxNode extends AbstractFlowNode<ReservationRequest, ReservationContext, RepairOutcome>`；在 `OrderLookupNode.next()` 加一行分支（或在既有节点内加一个出口） | `FlowNode` / `AbstractFlowNode` / `FlowEngine`（引擎零改动）；`SeckillReconciliationTask.repairReservations`（扫描循环、`FlowEngine.run` 调用、失败统计都不动） | `OrderLookupNode.java:35-44`、`SeckillReconciliationTask.java:83-98` |

一个**真实发生过的**扩展实例（不是假设）：`990d72f`（`feat(reconcile): add task compensation ledger with bounded retry`）新增了"重投超过上限转人工"这条分支，改动范围是：

| 改了哪 | 内容 | 位置 |
|---|---|---|
| `RepostNode.handle` | 开头加一段上限判断："超过 `max-retry-count` 则 `markFailed` + `FAILED_MANUAL`" | `RepostNode.java:52-61` |
| `RepairOutcome` | 加一个枚举值 `FAILED_MANUAL` | `RepairOutcome.java:20-21` |
| `RepostNode` / `ReleaseNode` / `RepairNode` | 各加一处账本写入（重投中 / 闭环成功） | `RepostNode.java:81-82`、`ReleaseNode.java:53`、`RepairNode.java:32` |
| 任务侧 `logOutcome` | `switch` 加一个 `case FAILED_MANUAL`（4 行，属于结果日志收口，不是分支逻辑） | `SeckillReconciliationTask.java:123-126` |

而 `OrderLookupNode.next()` 的三行分支、`SeckillReconciliationTask.repairReservations()` 的扫描与引擎调用，在这次扩展里一行都没动。这就是"新增分支不改主流程"最硬的证据。

```mermaid
flowchart LR
    A["新建 ChainNode 子类<br/>一个节点一件事"] --> B["OrderCreateChainConfig<br/>setNext 链条插入一处"]
    B --> C["节点 next() 接力<br/>执行器与编排层零改动"]
    D["新建 AbstractFlowNode 子类<br/>一个节点一种处置"] --> E["OrderLookupNode.next<br/>加一行分支声明"]
    E --> F["FlowEngine 驱动全树<br/>引擎与任务主流程零改动"]
```

#### 2. 节点为什么能独立单测（结构性原因）

不是靠测试框架，而是靠三条结构约束：

1. **依赖全部是注入字段**：`OrderIdempotentNode` 只有 `voucherOrderMapper`（`OrderIdempotentNode.java:18-19`）、`StockDeductNode` 只有 `seckillVoucherService`（`StockDeductNode.java:19-20`）、`UserLockNode` 只有 `redissonClient`（`UserLockNode.java:19-20`）、`RepostNode` 只有 `stringRedisTemplate` / `rocketMQTemplate` / `reconcileEndNode` / `taskCompensationRecorder`（`RepostNode.java:26-33`）——没有静态调用、没有 `new` 出来的协作者；
2. **节点无状态**：没有任何请求级成员变量，进度只通过 `ReservationContext` / `OrderCreateContext` 传递；
3. **契约极薄**：链的节点只有一个业务方法 `apply(request, context)`（`ChainNode.java:52`），接力靠 `next()`（`ChainNode.java:44`）；树的节点是 `process` + `next`（`FlowNode.java:13-16`）——断言对象只有"返回值 / 抛出的异常 / Context 的变化"。

`pom.xml:47-51` 引入了 `spring-boot-starter-test`（含 JUnit 5 与 Mockito），所以 mock 掉依赖直接 `new` 节点是可行的。

#### 3. 仓库里现有的真实单测

`src/test` 下与本次重构直接相关的是引擎/执行器层的两个测试类（业务节点侧见第 4 节说明）：

| 测试类 | 覆盖点 | 关键断言位置 |
|---|---|---|
| `ChainTest.java:16-131`（7 例） | 顺序接力并返回终点结果、**不接力提前结束**、异常中断并保留已执行轨迹、**装配期判环（自环 / 两节点环）**、重复 `setNext` 拒绝、空链拒绝 | `ChainTest.java:52-54`（顺序与终点结果）、`:67-68`（只执行到 skip）、`:87-88`（抛异常且 `c` 未执行）、`:96-99`（自环报错含 `a -> a`）、`:109-111`（环路含 `a -> b -> a`）、`:121-123`（重复串链拒绝）、`:128`（空链拒绝） |
| `FlowEngineTest.java:16-141` | 顺序执行并返回终点结果、条件分支跳转、终点收口、判环、环路路径、可配置步数上限、非正上限 | `FlowEngineTest.java:69-70`、`:83-84`、`:95-96`、`:109`、`:125-126`、`:130-132`、`:139-140` |

`FlowEngineTest` 里几个和"判环 + 步数上限"直接对应的断言，值得背下来（它同时证明了 5.2 第 5 节讲的实现细节）：

```java
// FlowEngineTest.java:87-97 —— 自环：走一步就报，且报错带 "loop -> loop"
TestNode node = new TestNode("loop", null, null, false);
node.then(node);
IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> FlowEngine.run(node, "x", new ArrayList<>()));
assertTrue(e.getMessage().contains("规则树检测到环"), e.getMessage());
assertTrue(e.getMessage().contains("loop -> loop"), e.getMessage());
```

```java
// FlowEngineTest.java:99-110 —— 双节点互指：第一次重访就报，报错带完整环路
TestNode a = new TestNode("a", null, null, false);
TestNode b = new TestNode("b", null, null, false);
a.then(b);
b.then(a);
...
assertTrue(e.getMessage().contains("a -> b -> a"), e.getMessage());
```

```java
// FlowEngineTest.java:112-133 —— 步数上限按传入值生效，且报错带已走路径
// a、b、c、end 共 4 个节点：上限 3 应在处理第 4 个节点前失败
IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> FlowEngine.run(a, "x", new ArrayList<>(), 3));
assertTrue(e.getMessage().contains("步数上限"), e.getMessage());
assertTrue(e.getMessage().contains("a -> b -> c"), e.getMessage());

// 上限放宽后正常执行
List<String> context = new ArrayList<>();
String result = FlowEngine.run(a, "x", context, 4);
assertEquals("end->result", result);
assertEquals(Arrays.asList("a", "b", "c", "end"), context);
```

假节点的写法就在同文件里（`FlowEngineTest.java:18-56` 的 `TestNode`、`ChainTest.java:19-39` 的 `RecordingNode`）：节点名、跳转条件、分支目标、是否终点都由构造函数参数给，`apply`（链）/ `process`（树）往 Context 列表里追加自己的名字——这样"执行了哪几步、什么顺序"一眼可断言。这也是业务节点单测可以照搬的骨架。

#### 4. 诚实说明：业务节点的单测还没有

- `src/test` 下共 6 个测试类（`RedisTest`、`RedissonTest`、`LoginTest`、`SeckillSwitchManagerTest`、`ChainTest`、`FlowEngineTest`），**其中没有针对 `OrderIdempotentNode` / `UserLockNode` / `UserVoucherIdempotentNode` / `StockDeductNode` / `SaveOrderNode` / `RepostNode` / `ReleaseNode` / `RepairNode` 的单测**（源码中未找到对应实现，待补）。
- 所以准确的说法是：**引擎与执行器语义已单测，节点"可独立单测"是结构上具备的能力，节点级用例如尚未补齐**。面试时不要把"可单测"说成"已经全部测了"，被要求看测试文件会很尴尬；正确说法是"执行器/引擎这一层有测试覆盖顺序、分支、终点、判环、步数上限；节点层依赖全为注入字段，可以脱离 Spring 直接构造，用例正在补"。
- 补测的最小样例（**待补，仓库中尚无此文件**，给出的是写法示意）：

```java
// 待补：src/test/java/com/hmdp/service/order/StockDeductNodeTest.java（仓库中尚无此文件）
class StockDeductNodeTest {

    @Mock
    private ISeckillVoucherService seckillVoucherService;

    @InjectMocks
    private StockDeductNode stockDeductNode;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void throwsWhenStockDeductAffectsNoRow() {
        VoucherOrder order = new VoucherOrder().setId(1L).setUserId(2L).setVoucherId(1001L);
        when(seckillVoucherService.update(any(LambdaUpdateWrapper.class))).thenReturn(false);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> stockDeductNode.process(order, new OrderCreateContext(order)));

        assertEquals("数据库库存与 Redis 预扣状态不一致", e.getMessage());
    }
}
```

#### 5. 这条怎么在面试里收口（一句话版本）

- 责任链：**顺序显式、一步一类、失败即抛、资源在编排层**——加一步只改装配处，主流程与既有节点零回归；
- 规则树：**分叉显式、终点统一、结果枚举化、引擎自带判环与可配置步数上限**——加一个处置动作只改 `next()` 或节点内部，任务主流程零回归；
- 可测试性：执行器与引擎已有单测（`ChainTest` 7 例，含**装配期判环**；`FlowEngineTest` 6 例，含运行期判环），节点因"依赖注入 + 无状态 + 薄契约"可以脱离 Spring 独立构造测试；节点级用例仍在补，说的时候要如实。


### 5.4 两套怎么选 + 搬迁指南

**一句话区分**：**链是"固定顺序的检验流水线"**（每步都要过，一步不过就断）；**树是"按状态分叉的决策结构"**（下一步是谁取决于数据）。

**5.4.1 逐项对比**

| | 责任链（链表版） | 规则树（引擎版） |
| --- | --- | --- |
| 下一跳 | 节点持 `next` 指针，接力时调 `next(request, context)`；**节点无权改顺序**（顺序在装配处定死） | 节点 `next()` 返回对象引用，**可按数据 if-else 分支** |
| 驱动方式 | 节点递归接力（终点的 `return` 沿调用栈带回） | 引擎 `while` 跟着 `next()` 走，`null` 结束 |
| 装配方式 | `setNext` 串链、**一处声明全序**（`Chain.assemble` 收口） | 节点之间 `@Resource` 互相注入（下一个是谁写在节点里） |
| 数据传递 | Context | Context |
| 返回值 | 节点的 `apply()` 返回 `R`，终点产出、中间节点原样带回 | **终点 `process` 的返回值 = `run()` 的结果** |
| 提前结束 | 节点**不调 `next()`**、直接 `return` 结果 | 某节点 `next()` 返回 `null`，或跳到另一个分支 |
| 失败语义 | 抛异常中断 + 上层事务回滚 | 抛异常中断 + 上层按"单条失败"统计 |
| 自带防护 | **装配期静态判环**（`Chain.assemble`）+ `setNext` 只允许一次；运行期零开销 | **运行期判环**（按对象身份）+ 可配置步数上限 |
| 框架代码量 | 2 个类、151 行 | 3 个类、131 行 |
| 适用场景 | 一串关卡顺序全过：**订单创建、退单校验、任何"体检"式流程** | 有分支的处置决策：**对账补偿、状态流转、审核** |
| 本项目落点 | `service/order`（5 个节点） | `listener/reconcile`（6 个节点） |

**5.4.2 搬到其他项目：只改三处**

1. **上下文**：把 `OrderCreateContext` / `ReservationContext` 的字段换成你业务里"过程中产生、后面还要用"的数据；
2. **节点业务**：链改 `apply()` 返回结果（校验通过 `return next(...)`、提前结束直接 `return` 结果、出错 `throw`）；树改 `handle()`，分支写在 `next()` 里；
3. **装配**：链用 `setNext` 串一遍、再用 `Chain.assemble(head)` 收口做判环；树在节点里的 `@Resource` + `next()` 声明跳转/分支。

**框架（`framework/chain`、`framework/rule`）原样不动**——零第三方依赖、不写注解、不读配置，这是它作为模板的全部价值。

**5.4.3 五条铁律（照抄时最容易踩的坑）**

1. **节点是单例，绝不存请求级数据**——一切放 Context。`ReservationContext` 的源码注释里就是这句原话："节点是单例，任何'一次请求的数据'都必须放在这里，不能放节点成员变量"；
2. **异常即中断**：节点里只 `throw` 不 `catch`，业务异常冒泡到编排层/任务统一处理（本项目：链 → 事务回滚 + MQ 重试；树 → 单条失败统计 + XXL-JOB 重试）；
3. **装配只做一次**：`setNext`、`@Resource`、`Chain.assemble` 都是启动期行为，别在每次请求里装配；链的 `setNext` 只允许调用一次（重复设置直接抛异常）；
4. **上下文一次请求一个**：`new OrderCreateContext(order)`、`new ReservationContext()` 都创建在调用处，节点之间共享同一个实例；
5. **判环要选对时机**：链是**静态结构**（下一跳由 `setNext` 定、不依赖数据）→ 判环放**装配期**做一次，启动即失败、运行期零开销；树是**动态结构**（`next()` 按数据分叉）→ 判环必须**每轮运行**做，并且额外留一个可配置的步数上限兜"合法但病态的长路径"。两者都用 `IdentityHashMap` 按**对象身份**记录访问过的节点（不是 `equals`），报错必须带**环路路径**，否则排查时等于没说。

**5.4.4 反过来问：什么时候不该用这两套**

- **分支只有两三个、且短期不会长**——直接 `if-else` 更清楚。本项目这一处之所以值得，是因为它有 7 个出口（3 个分支 + 跳过 + 上限 + 异常 + 终点）且还会继续长；
- **步骤之间有强数据依赖、每步都要上一步的产出**——顺序写下来反而更直观，"编排"会变成额外的心智负担；
- **需要跨进程/跨团队的可视化编排**——那是工作流引擎（Flowable 之类）的领域，这两个模板都覆盖不了，也不该硬套。

**5.4.5 附：本项目自己走过的两步（列表版 → 链表版）**

第 5 条这个重构其实做过两次，被追问"为什么又改一次"要能答上来：

| | 第一步（2026-10-06） | 第二步（2026-10-12） |
| --- | --- | --- |
| 形态 | **列表版**：`ChainNode` 是接口，`process` 返回 `boolean`；`Chain` 持 `List` 用 `for` 遍历 | **链表版**：`ChainNode` 是抽象类，持 `next` + `setNext`，`next()` 接力 |
| 结束怎么表达 | `return false` | 不调 `next()`、直接 `return` 结果 |
| 结果怎么带回 | 带不回（只能塞 Context，或者干脆不记） | `apply()` 的返回值沿调用栈带回 |
| 会成环吗 | 不会（跑的是有限列表），所以也没有"结构自检"的概念 | 会（`setNext` 写错），所以加了**装配期判环** |
| 顺序写在哪 | `Arrays.asList` | `setNext` 链条 |
| 各自的长处 | 顺序是"全局关注点"，一处看全；执行器可统一加日志/耗时 | 下一跳是"节点自己的契约"，更贴 GoF 责任链；能判环；结果类型能表达"为什么结束" |

改成链表版的三个理由：**① 结果可观测**——枚举带回编排层，日志能区分"创建成功"和"两种幂等跳过"；**② 结构可自检**——列表版结构上不可能成环、也就没有"装配正确性"这一说，链表版把风险显式化并用装配期判环挡在启动前；**③ 契约更贴模式**——节点自己知道下一跳，装配处只负责"串"。

代价也要说清：节点之间因此产生了引用耦合（`next`），链头要额外包一层（`Chain`），返回值语义比 `boolean` 复杂；而且 `execute` 不再能像列表版那样"顺手给每个节点统一加日志"——要加只能改基类的 `next()`。

一句话：**列表版简单直接，链表版可自检、可观测**。本项目最终选链表版，是因为除了"新增分支不改主流程"，还想把**装配正确性变成启动期就能证明的事**。

## 附录 A：代码英文 / 术语速查

代码里的类名、方法名都是英文单词（或缩写）拼出来的，看穿它们只需要一张对照表。

**业务名词**

| 英文                     | 中文                      | 出现位置                                               |
| ---------------------- | ----------------------- | -------------------------------------------------- |
| Voucher                | 优惠券（代金券）                | Voucher、VoucherOrder、SeckillVoucher、VoucherMapper… |
| Seckill                | 秒杀（second + kill 的合成说法） | seckillVoucher、`seckill:stock:`                    |
| Shop                   | 店铺                      | ShopController、shopId                              |
| Order                  | 订单                      | VoucherOrder、orderId                               |
| Stock                  | 库存                      | stock、`seckill:stock:{id}`                         |
| Status                 | 状态                      | order.status（1 待支付 / 2 已支付 / 4 已取消…）               |
| Title / SubTitle       | 标题 / 副标题（sub = 副、次一级）   | voucher.title、subTitle                             |
| Rules                  | 规则（使用说明）                | voucher.rules                                      |
| PayValue / ActualValue | 支付金额 / 抵扣金额             | payValue=8000（付 80 元）、actualValue=10000（抵 100 元）   |
| BeginTime / EndTime    | 开始时间 / 结束时间             | 秒杀活动起止                                             |
| xxxId                  | xxx 的编号                 | shopId、userId、voucherId、orderId                    |
| Reservation            | 预扣（先占下的记录）              | `seckill:reservation:`                             |
| Reconcile              | 对账                      | SeckillReconciliationTask                          |
| Compensation           | 补偿                      | seckill_rollback.lua（发送失败补偿）                       |
| Idempotent             | 幂等（重复执行结果不变）            | 幂等消费、幂等释放                                          |
| Callback               | 回调（对方反过来通知你）            | payCallback                                        |
| Release                | 释放（还回去）                 | seckill_rollback.lua（关单/对账释放，与补偿共用）                |
| Retry                  | 重试                      | retryCount、重投                                      |
| Timeout                | 超时                      | closeTimeoutOrder                                  |

**Java 类型 / 结构**

| 英文            | 中文                                     | 例子                                     |
| ------------- | -------------------------------------- | -------------------------------------- |
| Long          | 长整型数字（项目里的 ID 都用它）                     | Long shopId = 1L                       |
| Integer       | 整数                                     | Integer stock = 95                     |
| String        | 字符串                                    | "100元代金券"                              |
| Boolean       | 布尔值                                    | true / false                           |
| List<T>       | 列表（一队同类型数据）                            | List&lt;Voucher&gt; = [券1, 券2]         |
| Cache<K,V>    | 缓存（K → V 的表）                           | Cache&lt;Long, List&lt;Voucher&gt;&gt; |
| LocalDateTime | 本地日期时间                                 | 2022-10-09T00:00                       |
| DTO           | Data Transfer Object，数据传输对象（为某用途拼的数据包） | SeckillVoucherDetailDTO                |
| Bean          | Java 对象（Spring 的习惯叫法）                  | BeanUtil                               |
| Util          | Utility，工具类                            | JSONUtil、BeanUtil                      |
| Template      | 模板（封装好常用操作的工具）                         | StringRedisTemplate                    |

**方法 / 动词**

| 英文                    | 中文                         | 例子                                     |
| --------------------- | -------------------------- | -------------------------------------- |
| get / set             | 取 / 设                      | getIfPresent、setStock                  |
| put                   | 放（写入）                      | localVoucherCache.put(1, vouchers)     |
| delete                | 删除                         | stringRedisTemplate.delete(key)        |
| invalidate            | 作废（把缓存里某条删掉）               | localVoucherCache.invalidate(shopId)   |
| query / save / update | 查询 / 保存 / 更新               | queryVoucherOfShop、save、update         |
| increment             | 自增                         | retryCount +1                          |
| copyProperties        | 复制属性（对象间拷贝字段）              | BeanUtil.copyProperties                |
| execute               | 执行                         | execute(SCRIPT, …)                     |
| build                 | 构建（生成对象）                   | Caffeine.newBuilder().build()          |
| toJsonStr             | to JSON string，转成 JSON 字符串 | JSONUtil.toJsonStr(vouchers)           |
| toList / toBean       | JSON → List / 对象           | JSONUtil.toList(cached, Voucher.class) |

**Caffeine / Redis / 性能缩写**

| 英文                | 中文                                  |
| ----------------- | ----------------------------------- |
| getIfPresent      | 缓存里「存在才取」，没有返回 null                 |
| maximumSize       | 最大容量（最多存几个 key）                     |
| expireAfterWrite  | 写入后多久过期（读不续期）                       |
| expireAfterAccess | 最后一次访问后多久过期（读会续期）                   |
| opsForValue       | Redis「字符串值」的操作入口（get / set）         |
| TTL               | Time To Live，存活时间                   |
| L1 / L2 / L3      | 第 1/2/3 层缓存                         |
| QPS               | Queries Per Second，每秒请求数            |
| AOP               | Aspect Oriented Programming，面向切面编程  |
| MQ                | Message Queue，消息队列                  |
| LRU / LFU         | 最近最少使用 / 最不经常使用（两种缓存淘汰算法）           |
| W-TinyLFU         | Caffeine 使用的淘汰算法                    |
| Lua / EVAL        | Redis 内置的脚本语言 / 执行脚本的命令             |
| NX / EX           | Redis SET 的两个选项：NX=不存在才设置；EX=多少秒后过期 |
| CRON              | 定时表达式，如 `0 * * * * ?`               |
| SQL               | 数据库查询语言                             |
| JSON              | 一种文本数据格式                            |

按这张表读一遍，例如 `private final Cache<Long, List<Voucher>> localVoucherCache` 就是：**私有、最终的、缓存<数字→优惠券列表>、本地优惠券缓存**。
