package com.didicampus.infrastructure.grab;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCandidateQueueAdapterTest {

    @Test
    void offers_and_polls_lowest_score_candidate() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.range("errand:candidates:{17}", 0, 0))
                .thenReturn(new LinkedHashSet<>(java.util.Set.of("201")));
        RedisCandidateQueueAdapter adapter = new RedisCandidateQueueAdapter(redis);

        adapter.offer(17L, 201L, 12.5);
        Optional<Long> result = adapter.pollBest(17L);

        assertEquals(Optional.of(201L), result);
        verify(zsets).add("errand:candidates:{17}", "201", 12.5);
        verify(zsets).remove("errand:candidates:{17}", "201");
    }

    @Test
    void empty_queue_returns_empty_and_null_cardinality_becomes_zero() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.range(anyString(), anyLong(), anyLong())).thenReturn(null);
        when(zsets.zCard(anyString())).thenReturn(null);
        RedisCandidateQueueAdapter adapter = new RedisCandidateQueueAdapter(redis);

        assertEquals(Optional.empty(), adapter.pollBest(18L));
        assertEquals(0L, adapter.size(18L));
    }
}
