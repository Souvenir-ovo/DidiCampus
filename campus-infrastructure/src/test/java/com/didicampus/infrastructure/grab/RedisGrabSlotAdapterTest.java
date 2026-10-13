package com.didicampus.infrastructure.grab;

import com.didicampus.domain.grab.model.SlotOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisGrabSlotAdapterTest {

    @Test
    void lua_result_is_mapped_to_domain_outcome() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisScript<Long> acquire = mock(RedisScript.class);
        RedisScript<Long> rollback = mock(RedisScript.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        RedisGrabSlotAdapter adapter = new RedisGrabSlotAdapter(redis, acquire, rollback);

        assertEquals(SlotOutcome.ACQUIRED, adapter.tryAcquire(7L, 99L, "req-1"));
        verify(redis).execute(
                acquire,
                List.of("errand:slot:{7}", "errand:grabbed:{7}", "errand:idem:{7}:req-1"),
                "99",
                "300");
    }

    @Test
    void missing_script_result_fails_closed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisScript<Long> acquire = mock(RedisScript.class);
        RedisScript<Long> rollback = mock(RedisScript.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(null);

        RedisGrabSlotAdapter adapter = new RedisGrabSlotAdapter(redis, acquire, rollback);

        assertEquals(SlotOutcome.NOT_GRABBABLE, adapter.tryAcquire(7L, 99L, "req-2"));
    }

    @Test
    void initialization_and_rollback_use_cluster_safe_keys() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        RedisScript<Long> acquire = mock(RedisScript.class);
        RedisScript<Long> rollback = mock(RedisScript.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisGrabSlotAdapter adapter = new RedisGrabSlotAdapter(redis, acquire, rollback);

        adapter.initSlot(8L, 3, 60);
        adapter.rollback(8L, 99L, "req-3");

        verify(values).set("errand:slot:{8}", "3", Duration.ofSeconds(60));
        verify(redis).delete("errand:grabbed:{8}");
        verify(redis).execute(
                rollback,
                List.of("errand:slot:{8}", "errand:grabbed:{8}", "errand:idem:{8}:req-3"),
                "99");
    }
}
