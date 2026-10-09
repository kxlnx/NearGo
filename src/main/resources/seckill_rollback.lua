-- seckill_rollback.lua：预扣回滚脚本
-- 发送失败补偿 / 关单释放 / 对账释放 共用一个脚本（逻辑相同，仅触发时机不同）
-- KEYS[1]=库存 Key  KEYS[2]=名单 Key  KEYS[3]=预扣记录 Key  ARGV[1]=用户 ID
-- 只有名单里仍存在该用户时才回滚一次，避免重复执行导致库存多加。
if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then
    redis.call('srem', KEYS[2], ARGV[1])     -- 移出名单（资格还回去）
    redis.call('incrby', KEYS[1], 1)         -- 库存 +1
    redis.call('del', KEYS[3])               -- 删除预扣记录
    return 1                                 -- 1 = 本次真的回滚了
end
redis.call('del', KEYS[3])                   -- 已被其他流程释放过：只清预扣记录
return 0                                     -- 0 = 没做任何回滚
