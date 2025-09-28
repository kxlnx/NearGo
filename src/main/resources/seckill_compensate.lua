-- KEYS[1]: stock key
-- KEYS[2]: order-user set key
-- KEYS[3]: reservation hash key
-- ARGV[1]: user id
-- Confirmed send failure compensation: restore stock only when this user reservation exists.
if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then
    redis.call('srem', KEYS[2], ARGV[1])
    redis.call('incrby', KEYS[1], 1)
    redis.call('del', KEYS[3])
    return 1
end
redis.call('del', KEYS[3])
return 0
