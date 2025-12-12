# XXL-JOB 秒杀对账与自动补偿

## 目标

Redis Lua 预扣成功后，如果 RocketMQ 消息发送、消费或数据库落库发生异常，`seckill:reservation:{voucherId}:{userId}` 会留下可追踪的预扣元数据。XXL-JOB 周期触发 `seckillReconcileJob`，根据 Redis 与 MySQL 的状态执行补偿或修复。

## 处理分支

1. 数据库中不存在订单，并且预扣超过等待窗口：使用原订单 ID 重投 RocketMQ。
2. 数据库订单已经落库：修复 Redis 用户预占集合，并清理已完成的预扣元数据。
3. 数据库订单已经取消：通过 Lua 幂等释放 Redis 用户集合与库存。
4. 发现无法自动判断的历史脏数据：保留告警，不进行无依据的库存回补。

## XXL-JOB 配置建议

```text
AppName: neargo-executor
JobHandler: seckillReconcileJob
Cron: 0 * * * * ?
路由策略: FAILOVER
阻塞处理策略: 串行策略
失败重试次数: 1
```

应用侧配置均支持环境变量覆盖，示例见仓库根目录 `.env.example`。

## 当前验证范围

- Maven 编译验证通过。
- 对账、原订单 ID 重投和幂等释放逻辑已经落入源码。
- 实际部署时仍需启动 XXL-JOB Admin、注册执行器并创建调度任务，才能验证跨进程调度、失败重试和告警链路。

