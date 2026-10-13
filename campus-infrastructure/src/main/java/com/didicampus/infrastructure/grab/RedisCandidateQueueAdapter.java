package com.didicampus.infrastructure.grab;

import com.didicampus.domain.grab.ports.CandidateQueuePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * 使用 Redis ZSET 保存待流转的候选跑腿员。
 *
 * <p>分数越小越优先，应用层可以把抢单时间和信用分组合成这个分数。</p>
 */
@Component
@ConditionalOnProperty(name = "didicampus.grab.queue-mode", havingValue = "redis")
public class RedisCandidateQueueAdapter implements CandidateQueuePort {

    private static final String KEY_PREFIX = "errand:candidates:{";

    private final StringRedisTemplate redis;

    public RedisCandidateQueueAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void offer(long errandId, long runnerId, double score) {
        redis.opsForZSet().add(key(errandId), String.valueOf(runnerId), score);
    }

    @Override
    public Optional<Long> pollBest(long errandId) {
        Set<String> candidates = redis.opsForZSet().range(key(errandId), 0, 0);
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        String runnerId = candidates.iterator().next();
        redis.opsForZSet().remove(key(errandId), runnerId);
        return Optional.of(Long.parseLong(runnerId));
    }

    @Override
    public long size(long errandId) {
        Long count = redis.opsForZSet().zCard(key(errandId));
        return count == null ? 0L : count;
    }

    private String key(long errandId) {
        return KEY_PREFIX + errandId + "}";
    }
}
