-- 抢单名额的原子占位。
-- KEYS[1] 剩余名额，KEYS[2] 已抢用户集合，KEYS[3] 请求幂等键。
-- ARGV[1] 跑腿员 ID，ARGV[2] 幂等键 TTL。
-- 返回 1 成功，0 名额满，-1 已抢过，-2 重复请求，-3 任务不可抢。

local previous = redis.call('GET', KEYS[3])
if previous then
    if tonumber(previous) == 1 then
        return -2
    end
    return 0
end

local remaining = redis.call('GET', KEYS[1])
if not remaining then
    return -3
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

if tonumber(remaining) <= 0 then
    redis.call('SETEX', KEYS[3], tonumber(ARGV[2]), '0')
    return 0
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('SETEX', KEYS[3], tonumber(ARGV[2]), '1')
return 1
