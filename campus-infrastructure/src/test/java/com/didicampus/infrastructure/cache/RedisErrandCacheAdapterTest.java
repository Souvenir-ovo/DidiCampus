package com.didicampus.infrastructure.cache;

import com.didicampus.domain.errand.ports.ErrandCachePort;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisErrandCacheAdapterTest {

    @Test
    void readsLogicalExpiryEnvelopeAndPayload() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(
                "{\"exp\":4102444800000,\"empty\":false,\"data\":{\"id\":\"10\",\"status\":\"PUBLISHED\"}}");

        RedisErrandCacheAdapter adapter = newAdapter(redis, redisson, false);

        Optional<ErrandCachePort.CachedErrand> result = adapter.get(10L);

        assertTrue(result.isPresent());
        assertEquals("{\"id\":\"10\",\"status\":\"PUBLISHED\"}", result.get().payloadJson());
        assertFalse(result.get().isEmpty());
        assertFalse(result.get().logicallyExpired());
    }

    @Test
    void writesAllShardsAndCachesEmptyValue() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redis.opsForValue()).thenReturn(values);

        RedisErrandCacheAdapter adapter = newAdapter(redis, redisson, false);

        adapter.put(11L, "{\"id\":\"11\"}");
        adapter.putEmpty(12L);

        verify(values, times(8)).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void evictsEveryShardAndUsesDistributedRebuildLock() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, 10, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        RedisErrandCacheAdapter adapter = newAdapter(redis, redisson, false);

        assertTrue(adapter.tryAcquireRebuild(13L));
        adapter.releaseRebuild(13L);
        adapter.evict(13L);

        verify(lock).unlock();
        verify(redis).delete(anyList());
    }

    @Test
    void expiredPayloadIsReturnedForCallerToDecideRebuild() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(
                "{\"exp\":1,\"empty\":false,\"data\":{\"id\":\"14\"}}");

        RedisErrandCacheAdapter adapter = newAdapter(redis, redisson, false);

        ErrandCachePort.CachedErrand cached = adapter.get(14L).orElseThrow();

        assertTrue(cached.logicallyExpired());
        assertEquals("{\"id\":\"14\"}", cached.payloadJson());
    }

    private RedisErrandCacheAdapter newAdapter(StringRedisTemplate redis,
                                               RedissonClient redisson,
                                               boolean bloomEnabled) {
        return new RedisErrandCacheAdapter(
                redis,
                redisson,
                4,
                600,
                0,
                60,
                bloomEnabled);
    }
}
