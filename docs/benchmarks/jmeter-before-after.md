# 秒杀改造前后 A/B 压测报告（JMeter）

> 测试日期：2026-10-10　｜　改造前基线：`97cd6ab`（2022-10-10）　｜　改造后：`efaa50f`（HEAD）
> 单机压测，结论为**同机相对差异**，不外推集群容量。

## 0. 摘要

| 场景 | 指标 | 改造前 | 改造后 | 变化 |
|---|---|---|---|---|
| **稳态（人人成功）** 200 并发 × 5000 请求 | 接口 QPS | **342** | **1965** | **+475%（5.75 倍）** |
| | p50 | 517 ms | **28 ms** | −94.6% |
| | p95 | 592 ms | **110 ms** | −81.4% |
| | p99 | 795 ms | **134 ms** | −83.1% |
| **争抢（2000 用户抢 100）** 200 并发 × 2000 请求 | 成功路径 p50 | 198 ms | **65 ms** | −67.2% |
| | 成功路径 p95 | 303 ms | **88 ms** | −71.0% |
| | 拒绝路径 p95 | 315 ms | **117 ms** | −62.9% |
| **瞬时冲击** 2000 连接同时发起 | 连接被拒 | 1007（50.4%） | **606（30.3%）** | 多接住 **40.4%** 请求 |
| **代价** | 最终一致时间 | 0 s（同步写） | **10.4 s** | 异步化代价 |
| **守恒** | 落库能力 | 342 单/秒 | ~350–390 单/秒 | **基本不变** |

**正确性**：三组场景下不超卖、不重复下单、库存归零、Redis 集合一致 —— **全部通过**。

---

## 1. 口径定义（先说清，避免误读）

| 口径 | 线程数 | 循环 | 栅栏 | 请求数 | 库存 | 用途 |
|---|---|---|---|---|---|---|
| **A 整栈** | 2000 | 1 | groupSize=2000 | 2000 | 100 | 测**接入层接纳能力**（拒绝率），**不比业务 P95** |
| **B 干净** | 200 | 10 | 无 | 2000 | 100 | 测**业务路径延迟**（200 = Tomcat 线程数，不排队） |
| **C 容量** | 200 | 25 | 无 | 5000 | 200000 | 测**吞吐上限与最终一致时间**（人人有份） |

**三条统计约定**

1. **一用户一请求**：秒杀有"一人一单"+ 每用户 5/s 限流，复用用户测的不是秒杀链路。
2. **按业务结果分类统计**：秒杀业务失败也返回 `HTTP 200`，必须分开算分位（见 §4.2）。
3. **成功/拒绝分开报**：`OUT_OF_STOCK` 是快速拒绝路径，混进平均值会严重稀释成功路径的延迟。

---

## 2. 环境与基线

### 2.1 环境

| 项 | 值 |
|---|---|
| 机器 | 单机（可用内存 21.3 GB / 32 GB） |
| JDK | 运行统一 **JDK 17.0.14**；改造前基线仅**编译**用 JDK 8 |
| JVM | `-Xms2g -Xmx2g -XX:MaxMetaspaceSize=256m` |
| MySQL | 8.0.26，`innodb_flush_log_at_trx_commit=1`、`sync_binlog=1`、`innodb_buffer_pool_size=8MB`（未调整） |
| Redis | 5.0.14.1 单实例，RDB 落盘开启（未调整） |
| RocketMQ | 4.9.4 单 namesrv + 单 broker（`broker-local.conf`，ASYNC_FLUSH） |
| 被测应用 | 改造前 :8082（`hmdp-1.0-SNAPSHOT.jar`）／改造后 :8081（`neargo-1.0-SNAPSHOT.jar`） |
| 压测工具 | JMeter 5.6.3（非 GUI）＋ 自研虚拟线程压测器做交叉验证 |

### 2.2 改造前基线：从自己的 Git 历史构建

```bash
git worktree add --detach <dir> 97cd6ab
mvn -DskipTests package -f <dir>/pom.xml        # JDK 8 编译
java -jar <dir>/target/hmdp-1.0-SNAPSHOT.jar --server.port=8082
```

该提交的秒杀实现即"改造前"形态：`Redisson 用户锁 → 查库判断库存 → 条件更新扣减 → 插入订单`，全程同步且命中同一热点行。

**为了让老代码能连当前环境，只做了 2 处与业务无关的修改**（`git diff --stat` 可验证只有这 2 个文件）：

| 文件 | 改动 | 原因 |
|---|---|---|
| `SimpleRedisLock.java` | 删除一行 IDE 误导入的 `com.sun.xml.internal.ws.policy.privateutil.PolicyUtils` | JDK 9+ 编译失败，该 import 未被使用 |
| `RedissonConfig.java` | 增加空密码判断 | 本地 Redis 无密码，老代码无条件 `setPassword()` 会发 AUTH 被拒 |

> 秒杀业务代码**一行未改**。

---

## 3. 测试数据准备

### 3.1 秒杀券

用项目自身接口创建，**每组场景两张独立券**（改造前 / 改造后各一张），避免相互污染：

```
POST /voucher/seckill
{ "shopId":1, "title":"G1-before", "type":1, "stock":100,
  "beginTime":"<now-10min>", "endTime":"<now+24h>", ... }
```

### 3.2 用户 Token 池（关键）

秒杀接口 `/voucher-order/**` 在登录拦截器之后，必须携带 `authorization` 头；而"一人一单 + 每用户限流"意味着**每个请求必须是不同用户**。

走 2 万次真实登录需约 1 小时，因此改为**直接向 Redis 写入 token 哈希**（秒杀只读取 `id` 字段）：

```lua
-- fabricate_tokens2.lua  用法: redis-cli --eval fabricate_tokens2.lua , 20000 86400
for i = 1, n do
  redis.call('hset', 'login:token:bench'..i, 'id', tostring(900000+i), 'nickName', 'bench'..i, 'icon', '')
  redis.call('expire', 'login:token:bench'..i, ttl)
end
```

校验：`HGETALL login:token:bench1` 含 `id` 字段、`TTL` 为 86400。随后生成 JMeter 读取的 CSV（格式 `序号,手机号,token`），**两轮复用同一批 token** 以控制变量。

---

## 4. JMeter 测试计划

### 4.1 计划结构

```
Test Plan
├─ HTTP Request Defaults   domain/port、connect 10s、response 180s、HttpClient4、contentEncoding=UTF-8
├─ Thread Group            num_threads / ramp_time / loops
│   ├─ CSV Data Set Config filename=tokens_big.csv，变量 userId,phone,token
│   │                      Sharing=All threads，Recycle=false，StopThread=true
│   ├─ [Synchronizing Timer]  groupSize=线程数（仅口径 A 使用）
│   ├─ Header Manager      authorization = ${token}
│   └─ HTTP Sampler        POST /voucher-order/seckill/${voucherId}
│       └─ JSR223 PostProcessor (groovy)   ← 业务结果分类
└─ （不加任何 Listener，结果只落 .jtl）
```

### 4.2 为什么必须做业务结果分类

秒杀接口业务失败也返回 **HTTP 200**：

```json
{"success":false,"errorMsg":"库存不足"}   ← HTTP 200
{"success":true,"data":6466379...}        ← HTTP 200
```

JMeter 默认只看状态码 → 会把约 95% 的业务拒绝计为"成功"，P95 被严重拉低。因此在采样器上挂 JSR223 分类器：

```groovy
def body = prev.getResponseDataAsString(); def code = prev.getResponseCode(); def o;
if (!"200".equals(code)) { o = "HTTP" + code; }
else if (body.contains('"success":true')) { o = "SUCCESS"; }
else if (body.contains('库存不足')) { o = "OUT_OF_STOCK"; }
else if (body.contains('不能重复下单') || body.contains('禁止重复购买')) { o = "DUPLICATE"; }
else if (body.contains('操作过于频繁')) { o = "RATE_LIMITED"; }
else if (body.contains('请先登录')) { o = "NOT_LOGIN"; }
else { o = "OTHER"; }
vars.put("outcome", o);
```

通过 `-Jsample_variables=outcome` 把分类结果作为一列写入 `.jtl`，分析时按类别分别出分位。

### 4.3 三个必须避开的坑（实测踩过）

| 坑 | 现象 | 修法 |
|---|---|---|
| 数字字段写 `${__P(threads,2000)}` | XStream 解析报 `NumberFormatException`，计划无法加载 | **模板 + 运行前渲染成字面值** |
| Groovy 中的 `&&` | XML 非法 | 写成 `&amp;&amp;` |
| 计划里放 `JSONPostProcessor` | **请求已发出，但 `.jtl` 一条样本都不落盘**，HTML 报告生成失败 | **移除，分类全部交给 JSR223** |

---

## 5. 执行步骤（可复现）

```powershell
# 1) 起两套被测应用（改造前 :8082 / 改造后 :8081），并关闭 xxl-job 注册避免干扰
java -jar neargo-1.0-SNAPSHOT.jar --xxl.job.admin.addresses=

# 2) 造券 + 造 token 池 + 生成 CSV（见 §3）

# 3) 运行 JMeter（非 GUI）
$env:HEAP='-Xms2g -Xmx2g -XX:MaxMetaspaceSize=256m'
jmeter -n -t <plan.jmx> -l <result.jtl> -e -o <reportDir> `
       -Jsampleresult.default.encoding=UTF-8 `
       -Jsample_variables=outcome
```

| 参数 | 作用 |
|---|---|
| `-n` | 非 GUI 模式 |
| `-l` | 原始样本落盘（CSV，含自定义列 `outcome`） |
| `-e -o` | 生成 HTML 报告 |
| `-Jsampleresult.default.encoding=UTF-8` | 响应体按 UTF-8 解码，否则中文 `errorMsg` 乱码、分类失效 |
| `-Jsample_variables=outcome` | 分类结果写入 `.jtl` 作为独立列 |

每轮固定顺序：**记录基线状态 → 跑改造前 → 立刻取数 → 跑改造后 → 立刻取数 → 1 分钟后再取一次**。

---

## 6. 断言与取数

```sql
select count(*) from tb_voucher_order where voucher_id=?;                      -- 应等于初始库存
select count(*) from (select user_id from tb_voucher_order
                      where voucher_id=? group by user_id having count(*)>1) t; -- 必须为 0
select stock from tb_seckill_voucher where voucher_id=?;                        -- 必须为 0
```

```
redis-cli get   seckill:stock:<id>    # 改造后应为 0
redis-cli scard seckill:order:<id>    # 改造后应等于初始库存
```

> **时间陷阱**：延迟关单会在下单约 1 分钟后关闭未支付订单并**回补库存**。因此
> ①「库存归零」类断言必须在压测结束**立刻**取数；
> ② 1 分钟后再取一次，用于验证"关单 + 回补"链路（本次实测：券被抢光 → 1 分钟后 100 张全部回补）。

---

## 7. 结果

### 7.1 口径 A：整栈（2000 连接瞬时冲击，库存 100）

| | 改造前 | 改造后 |
|---|---|---|
| 业务响应（拿到明确答复） | 993（100 成功 + 893 库存不足） | **1394**（100 成功 + 1294 库存不足） |
| **Connection refused（TCP 层被拒）** | **1007（50.4%）** | **606（30.3%）** |
| 墙钟 | 0.66 s | 0.70 s |
| 订单 / DB 库存 / 重复用户 | 100 / 0 / 0 ✅ | 100 / 0 / 0 ✅ |
| Redis 库存 / 已下单集合 | 不适用（老实现不用 Redis 库存） | 0 / 100 ✅ |

**解读**：2000 个连接同时到达，超过 Tomcat `acceptCount=100` + 200 工作线程的接纳能力，排不进 accept 队列的连接被 RST。改造后请求路径不再占用数据库连接（Hikari 池仅 10），成功路径 p50 从 303 ms 降到 140 ms，线程释放更快 → **同一窗口多接住 401 个请求（+40.4%）**，用户从"页面报错"变成"明确告知没抢到"。
**本口径含接入层排队，不用于比较业务 P95。**

### 7.2 口径 B：干净（200 并发 × 10 循环，库存 100）

| 分类 | 改造前 | 改造后 | 变化 |
|---|---|---|---|
| **SUCCESS** | 100 个：p50 **198** ／ p95 **303** ／ p99 **314** ／ max 314 ms | 100 个：p50 **65** ／ p95 **88** ／ p99 **89** ／ max 91 ms | p50 −67.2%、p95 −71.0%、p99 −71.7% |
| OUT_OF_STOCK | 1900 个：p50 2 ／ p95 **315** ／ p99 348 ／ max 369 ms | 1878 个：p50 37 ／ p95 **117** ／ p99 136 ／ max 151 ms | p95 −62.9% |
| Connection refused | 0 | 22（1.1%） | — |
| 墙钟 / 总 QPS | 1.34 s / 1495.9 | 1.31 s / 1522.1 | 基本持平 |
| 断言 | 订单 100、DB 库存 0、重复 0 ✅ | 订单 100、DB 库存 0、重复 0、Redis 库存 0、集合 100 ✅ | — |

**长尾被削平**：

```
改造前：p50 =   2 ms，p95 = 315 ms   （相差 157 倍，少数请求拖出长尾）
改造后：p50 =  37 ms，p95 = 117 ms   （相差 3 倍，分布均匀）
```

拒绝路径 p50 从 2 ms 升到 37 ms 的原因：改造后每个请求多了 4 次 Redis 往返（限流 Lua、动态开关、雪花 ID、秒杀 Lua），而 Lettuce 连接池 `max-active=10` 且 Redis 单线程，200 并发下这部分排队约 35 ms。**这是为"限流 + 灰度 + 可对账"付出的代价。**

### 7.3 口径 C：容量（200 并发 × 25 循环 = 5000 请求，库存 20 万，人人成功）

| 指标 | 改造前 | 改造后 | 变化 |
|---|---|---|---|
| 样本 | 5000 SUCCESS | 5000 SUCCESS | — |
| **接口 QPS** | **342** | **1965.4** | **+475%（5.75 倍）** |
| p50 | 517 ms | **28 ms** | −94.6% |
| p95 | 592 ms | **110 ms** | −81.4% |
| p99 | 795 ms | **134 ms** | −83.1% |
| max | 1061 ms | 153 ms | −85.6% |
| 墙钟 | 14.62 s | 2.54 s | −82.6% |
| JMeter 控制台 | 5000 in 14 s = 364.7/s，Err 0 | 5000 in 2 s = 2003.2/s，Err 0 | — |
| **接口段结束时订单数** | 5000（同步写库） | 1334（异步落库中） | — |
| **最终一致时间** | 0 s | **10.4 s** | 异步化代价 |
| 落库速率 | 342 单/秒 | ~350–390 单/秒 | **基本不变** |
| 断言 | 订单 5000、库存 195000、重复 0 ✅ | 订单 5000、库存 195000、重复 0、Redis 集合 5000 ✅ | — |

### 7.4 双工具交叉验证

同一配置（口径 C，改造前）分别用 JMeter 与自研虚拟线程压测器执行：

| 工具 | QPS | p50 |
|---|---|---|
| JMeter 5.6.3 | 342 | 517 ms |
| 自研压测器 | 372 | 527 ms |
| 偏差 | 8% | 2% |

---

## 8. 结论

1. **用户侧延迟是数量级改善**：稳态 p50 **517 → 28 ms**；争抢场景成功路径 p50 **198 → 65 ms**。
2. **全成功吞吐提升 5.75 倍**：**342 → 1965 QPS**。
3. **瞬时冲击下的接纳能力提升 40%**：2000 连接并发时，连接被拒率 **50.4% → 30.3%**，业务响应数 **993 → 1394**。
4. **长尾被削平**：拒绝路径 p95 **315 → 117 ms**；对 SLO 而言 P95/P99 比平均值更重要。
5. **工作量守恒（最重要的发现）**：改造前同步吞吐 **342 单/秒**，改造后消费端落库能力 **~350–390 单/秒**，**几乎相同**。异步化并没有减少系统总工作量，而是把等待从用户请求路径挪到后台，代价是 **最终一致时间 10.4 秒**。
   说明当前瓶颈已从"入口"转移到"消费端写库"（同一热点行 + 每次提交两次 fsync），**继续提升需要解决落库侧**（分片/拆热点行/异步对账）。

---

## 9. 边界与未覆盖

**边界**

1. 单机压测，压测客户端与服务端同机竞争 CPU 与内存；
2. 口径 A 含接入层排队与连接拒绝，**不用于跨版本比较业务延迟**；
3. MySQL `innodb_buffer_pool_size=8MB`、Redis RDB 落盘开启均未调整，对两个版本同时生效；
4. 结论为**同机相对差异**，不外推集群容量。

**未覆盖**

1. **读路径缓存**（热点店铺逻辑过期、券详情两级缓存）未纳入本轮压测；
2. **异步一致性链路**（对账重投、补偿账本终态）无法用正常压测证明，需故障注入验证；
3. 未做长时间稳定性与内存泄漏观察；
4. 生产环境应在应用前置网关排队/限流，应用层不会直接承受数千连接冲击。

---

## 10. 复现清单

| 文件 | 说明 |
|---|---|
| `g1-burst.jmx` | 口径 A/B 计划（含栅栏与分类器） |
| `g2-fixed.jmx` | 口径 C 计划（固定请求数） |
| `g2-steady.jmx` | 口径 C 计划（时长驱动变体） |
| `fabricate_tokens2.lua` | 批量伪造 token 哈希 |
| `analyze.ps1` | 读 `.jtl` 按业务结果分类输出分位与 QPS |
| `g1c-before.jtl` / `g1c-after.jtl` | 口径 B 原始样本（各 2000 条，含 `outcome` 列） |
| `g1-before.jtl` / `g1-after.jtl` | 口径 A 原始样本 |
| `g2-before.jtl` / `g2-after.jtl` | 口径 C 原始样本（各 5000 条） |
| `report/*/index.html` | JMeter HTML 报告 |

> 依仓库既有约定（见 [本地压测摘要](./README.md)），**仅提交汇总数据**，不上传登录 Token、个人信息与逐请求原始文件。
