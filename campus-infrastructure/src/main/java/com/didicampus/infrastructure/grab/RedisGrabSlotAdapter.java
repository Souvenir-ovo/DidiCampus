package com.didicampus.infrastructure.grab;

import com.didicampus.domain.grab.model.SlotOutcome;
import com.didicampus.domain.grab.ports.GrabSlotPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 使用 Redis Lua 脚本完成抢单名额占位。
 *
 * <p>剩余名额、已抢用户集合和幂等键使用相同 hash tag，
 * 这样迁移到 Redis Cluster 后仍能由同一个脚本原子操作。</p>
 */
@Component
@ConditionalOnProperty(name = "didicampus.grab.slot-mode", havingValue = "redis")
public class RedisGrabSlotAdapter implements GrabSlotPort {

    private static final long REQUEST_TTL_SECONDS = 300L;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> acquireScript;
    private final RedisScript<Long> rollbackScript;

    public RedisGrabSlotAdapter(StringRedisTemplate redis,
                                @Qualifier("grabScript") RedisScript<Long> acquireScript,
                                @Qualifier("rollbackSlotScript") RedisScript<Long> rollbackScript) {
        this.redis = redis;
        this.acquireScript = acquireScript;
        this.rollbackScript = rollbackScript;
    }

    @Override
    public SlotOutcome tryAcquire(long errandId, long runnerId, String requestId) {
        Long code = redis.execute(
                acquireScript,
                List.of(slotKey(errandId), grabbedKey(errandId), requestKey(errandId, requestId)),
                String.valueOf(runnerId),
                String.valueOf(REQUEST_TTL_SECONDS));
        return code == null ? SlotOutcome.NOT_GRABBABLE : SlotOutcome.fromCode(code);
    }

    @Override
    public void rollback(long errandId, long runnerId, String requestId) {
        redis.execute(
                rollbackScript,
                List.of(slotKey(errandId), grabbedKey(errandId), requestKey(errandId, requestId)),
                String.valueOf(runnerId));
    }

    @Override
    public void initSlot(long errandId, int slotTotal, long ttlSeconds) {
        redis.opsForValue().set(
                slotKey(errandId),
                String.valueOf(Math.max(0, slotTotal)),
                Duration.ofSeconds(Math.max(1L, ttlSeconds)));
        redis.delete(grabbedKey(errandId));
    }

    @Override
    public long remainingSlot(long errandId) {
        String remaining = redis.opsForValue().get(slotKey(errandId));
        return remaining == null ? -1L : Long.parseLong(remaining);
    }

    private String slotKey(long errandId) {
        return "errand:slot:{" + errandId + "}";
    }

    private String grabbedKey(long errandId) {
        return "errand:grabbed:{" + errandId + "}";
    }

    private String requestKey(long errandId, String requestId) {
        return "errand:idem:{" + errandId + "}:" + requestId;
    }
}
