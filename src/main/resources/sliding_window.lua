-- KEYS[1]: sliding window key
-- ARGV[1]: window seconds
-- ARGV[2]: max requests
-- ARGV[3]: current time in milliseconds
local key = KEYS[1]
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

redis.call('zremrangebyscore', key, 0, now - window * 1000)
local current = redis.call('zcard', key)
if current >= limit then
    return 0
end

redis.call('zadd', key, now, now .. '-' .. math.random(100000, 999999))
redis.call('expire', key, window)
return 1
