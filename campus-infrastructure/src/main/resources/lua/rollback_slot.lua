-- 抢单落库失败后的补偿回滚。
-- 只有确实从已抢集合移除用户时才补回名额，因此重复执行不会虚增名额。

if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
    redis.call('INCR', KEYS[1])
    redis.call('DEL', KEYS[3])
    return 1
end
return 0
