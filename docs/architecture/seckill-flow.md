# 秒杀、关单与对账流程

NearGo 将请求接入、库存资格判断、订单落库和故障补偿拆成多个阶段，避免秒杀请求直接冲击 MySQL。

```mermaid
flowchart LR
    A[秒杀请求] --> B[Redis Lua 原子校验]
    B -->|库存不足或重复下单| C[直接拒绝]
    B -->|预扣成功| D[记录 reservation 元数据]
    D --> E[RocketMQ 秒杀订单消息]
    E --> F[SeckillOrderListener]
    F --> G[订单 ID / 用户券幂等校验]
    G --> H[MySQL 条件扣库存并创建订单]
    H --> I[RocketMQ 延迟关单消息]
    I --> J{订单仍为待支付?}
    J -->|是| K[条件关单并回补库存]
    J -->|否| L[保持已支付状态]
    D --> M[XXL-JOB 对账任务]
    M --> N{数据库订单状态}
    N -->|未落库| O[使用原订单 ID 重投 RocketMQ]
    N -->|已取消| P[Lua 幂等释放 Redis 预扣]
    N -->|已落库| Q[修复预占集合并清理元数据]
```

## 关键代码

| 能力 | 实现位置 |
|---|---|
| Redis 库存校验、扣减、用户去重与预扣记录 | `src/main/resources/seckill.lua` |
| RocketMQ 消息发送及发送失败补偿 | `VoucherOrderServiceImpl#seckillVoucher` |
| 消费端幂等与数据库条件扣库存 | `VoucherOrderServiceImpl#createVoucherOrder` |
| 秒杀订单消费 | `SeckillOrderListener` |
| 延迟关单 | `OrderTimeoutListener` |
| 支付与关单状态竞争 | `VoucherOrderServiceImpl#payCallback`、`closeTimeoutOrder` |
| Redis 预扣幂等释放 | `src/main/resources/seckill_release.lua` |
| 定时对账和消息重投 | `SeckillReconciliationTask` |

## 一致性边界

- Lua 脚本保证 Redis 内库存、用户集合和预扣元数据原子变更。
- RocketMQ 发送失败时执行补偿脚本，回滚本次 Redis 预扣。
- 消费端同时使用订单 ID 和“用户 + 优惠券”进行幂等校验。
- 支付和超时关单均以待支付状态为更新条件，只允许一个状态迁移成功。
- 对账任务使用原订单 ID 重投，避免补偿时生成新的业务订单。

