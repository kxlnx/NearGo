-- KEYS[1]: stock key
-- KEYS[2]: order-user set key
-- KEYS[3]: reservation hash key
-- ARGV[1]: user id
-- 只在 Redis 中仍存在预扣用户时释放一次，避免重复关单导致重复回补库存。
if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then
    redis.call('srem', KEYS[2], ARGV[1])
    redis.call('incrby', KEYS[1], 1)
    redis.call('del', KEYS[3])
    return 1
end
-- 预扣已经被其他补偿流程释放时，只清理残留元数据，不再次增加库存。
redis.call('del', KEYS[3])
return 0
