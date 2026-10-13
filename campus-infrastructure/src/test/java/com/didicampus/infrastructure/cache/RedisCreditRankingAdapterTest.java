package com.didicampus.infrastructure.cache;

import com.didicampus.domain.credit.ports.CreditRankingPort;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCreditRankingAdapterTest {

    @Test
    void returns_highest_scores_first() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zsets);
        ZSetOperations.TypedTuple<String> first =
                new DefaultTypedTuple<>("801", 98.0);
        ZSetOperations.TypedTuple<String> second =
                new DefaultTypedTuple<>("802", 91.0);
        when(zsets.reverseRangeWithScores("credit:rank:3", 0, 1))
                .thenReturn(new LinkedHashSet<>(List.of(first, second)));
        RedisCreditRankingAdapter adapter = new RedisCreditRankingAdapter(redis);

        adapter.update(3L, 801L, 98);
        List<CreditRankingPort.Entry> result = adapter.top(3L, 2);

        assertEquals(List.of(
                new CreditRankingPort.Entry(801L, 98),
                new CreditRankingPort.Entry(802L, 91)), result);
        verify(zsets).add("credit:rank:3", "801", 98.0);
    }

    @Test
    void redis_failure_degrades_to_empty_ranking() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("redis unavailable"));
        RedisCreditRankingAdapter adapter = new RedisCreditRankingAdapter(redis);

        assertEquals(List.of(), adapter.top(3L, 5));
    }
}
