package com.didicampus.infrastructure.cache;

import com.didicampus.domain.credit.ports.CreditRankingPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis ZSET 信用排行。
 *
 * <p>排行只是信用事实的查询加速和展示能力，Redis 故障时返回空榜，
 * 不影响 MySQL 中信用分事件的真实记录。</p>
 */
@Component
@ConditionalOnProperty(name = "didicampus.credit.ranking-mode", havingValue = "redis")
public class RedisCreditRankingAdapter implements CreditRankingPort {

    private static final String KEY_PREFIX = "credit:rank:";

    private final StringRedisTemplate redis;

    public RedisCreditRankingAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void update(long campusId, long userId, int score) {
        try {
            redis.opsForZSet().add(KEY_PREFIX + campusId, String.valueOf(userId), score);
        } catch (RuntimeException ignored) {
            // 排行榜降级不影响信用分主流程。
        }
    }

    @Override
    public List<Entry> top(long campusId, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        try {
            Set<ZSetOperations.TypedTuple<String>> rows =
                    redis.opsForZSet().reverseRangeWithScores(
                            KEY_PREFIX + campusId,
                            0,
                            limit - 1L);
            if (rows == null) {
                return List.of();
            }

            List<Entry> result = new ArrayList<>(rows.size());
            for (ZSetOperations.TypedTuple<String> row : rows) {
                if (row.getValue() != null && row.getScore() != null) {
                    result.add(new Entry(
                            Long.parseLong(row.getValue()),
                            row.getScore().intValue()));
                }
            }
            return result;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}
