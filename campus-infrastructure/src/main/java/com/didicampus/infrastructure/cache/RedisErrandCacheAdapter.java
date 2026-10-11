package com.didicampus.infrastructure.cache;

import com.didicampus.domain.errand.ports.ErrandCachePort;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "didicampus.cache.mode", havingValue = "redis")
public class RedisErrandCacheAdapter implements ErrandCachePort {

    private static final Logger log = LoggerFactory.getLogger(RedisErrandCacheAdapter.class);
    private static final String DETAIL_PREFIX = "didicampus:errand:detail:";
    private static final String REBUILD_PREFIX = "didicampus:errand:rebuild:";
    private static final String BLOOM_NAME = "didicampus:errand:bloom";

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final int shardCount;
    private final long detailTtlSeconds;
    private final long ttlJitterSeconds;
    private final long emptyTtlSeconds;
    private final boolean bloomEnabled;

    public RedisErrandCacheAdapter(
            StringRedisTemplate redis,
            RedissonClient redisson,
            @Value("${didicampus.cache.redis.shards:4}") int shardCount,
            @Value("${didicampus.cache.redis.ttl-seconds:600}") long detailTtlSeconds,
            @Value("${didicampus.cache.redis.jitter-seconds:120}") long ttlJitterSeconds,
            @Value("${didicampus.cache.redis.empty-ttl-seconds:60}") long emptyTtlSeconds,
            @Value("${didicampus.cache.redis.bloom-enabled:true}") boolean bloomEnabled) {
        this.redis = redis;
        this.redisson = redisson;
        this.shardCount = Math.max(1, shardCount);
        this.detailTtlSeconds = Math.max(60, detailTtlSeconds);
        this.ttlJitterSeconds = Math.max(0, Math.min(ttlJitterSeconds, this.detailTtlSeconds - 1));
        this.emptyTtlSeconds = Math.max(1, emptyTtlSeconds);
        this.bloomEnabled = bloomEnabled;
    }

    @Override
    public Optional<CachedErrand> get(long errandId) {
        try {
            int shard = ThreadLocalRandom.current().nextInt(shardCount);
            String raw = redis.opsForValue().get(key(errandId, shard));
            return raw == null ? Optional.empty() : Optional.of(readEnvelope(raw));
        } catch (RuntimeException ex) {
            log.warn("redis cache read failed, fallback to database, errandId={}", errandId, ex);
            return Optional.empty();
        }
    }

    @Override
    public void put(long errandId, String payloadJson) {
        long physicalTtl = detailTtlSeconds + randomJitter();
        long logicalExpireAt = System.currentTimeMillis() + physicalTtl * 500L;
        String envelope = "{\"exp\":" + logicalExpireAt
                + ",\"empty\":false,\"data\":" + payloadJson + "}";
        writeToAllShards(errandId, envelope, Duration.ofSeconds(physicalTtl));
        registerExisting(errandId);
    }

    @Override
    public void putEmpty(long errandId) {
        long logicalExpireAt = System.currentTimeMillis() + emptyTtlSeconds * 1000L;
        String envelope = "{\"exp\":" + logicalExpireAt + ",\"empty\":true,\"data\":null}";
        writeToAllShards(errandId, envelope, Duration.ofSeconds(emptyTtlSeconds));
    }

    @Override
    public void evict(long errandId) {
        try {
            List<String> keys = new ArrayList<>(shardCount);
            for (int shard = 0; shard < shardCount; shard++) {
                keys.add(key(errandId, shard));
            }
            redis.delete(keys);
        } catch (RuntimeException ex) {
            log.error("redis cache eviction failed, errandId={}", errandId, ex);
        }
    }

    @Override
    public boolean tryAcquireRebuild(long errandId) {
        try {
            RLock lock = redisson.getLock(REBUILD_PREFIX + errandId);
            return lock.tryLock(0, 10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException ex) {
            log.warn("redis rebuild lock failed, errandId={}", errandId, ex);
            return false;
        }
    }

    @Override
    public void releaseRebuild(long errandId) {
        try {
            RLock lock = redisson.getLock(REBUILD_PREFIX + errandId);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException ex) {
            log.warn("redis rebuild unlock failed, errandId={}", errandId, ex);
        }
    }

    @Override
    public boolean mightExist(long errandId) {
        if (!bloomEnabled) {
            return true;
        }
        try {
            RBloomFilter<Long> bloom = redisson.getBloomFilter(BLOOM_NAME);
            if (!bloom.isExists()) {
                return true;
            }
            return bloom.contains(errandId);
        } catch (RuntimeException ex) {
            log.warn("redis bloom lookup failed, fail open, errandId={}", errandId, ex);
            return true;
        }
    }

    @Override
    public void registerExisting(long errandId) {
        if (!bloomEnabled) {
            return;
        }
        try {
            RBloomFilter<Long> bloom = redisson.getBloomFilter(BLOOM_NAME);
            if (!bloom.isExists()) {
                bloom.tryInit(100_000L, 0.01);
            }
            bloom.add(errandId);
        } catch (RuntimeException ex) {
            log.warn("redis bloom register failed, errandId={}", errandId, ex);
        }
    }

    private void writeToAllShards(long errandId, String value, Duration ttl) {
        try {
            for (int shard = 0; shard < shardCount; shard++) {
                redis.opsForValue().set(key(errandId, shard), value, ttl);
            }
        } catch (RuntimeException ex) {
            log.warn("redis cache write failed, errandId={}", errandId, ex);
        }
    }

    private String key(long errandId, int shard) {
        return DETAIL_PREFIX + errandId + ":" + shard;
    }

    private long randomJitter() {
        if (ttlJitterSeconds == 0) {
            return 0;
        }
        return ThreadLocalRandom.current().nextLong(-ttlJitterSeconds, ttlJitterSeconds + 1);
    }

    private CachedErrand readEnvelope(String raw) {
        long logicalExpireAt = numberField(raw, "exp");
        boolean empty = raw.contains("\"empty\":true");
        if (empty) {
            return new CachedErrand(null, logicalExpireAt, true);
        }
        int dataMarker = raw.indexOf("\"data\":");
        if (dataMarker < 0 || raw.isEmpty() || raw.charAt(raw.length() - 1) != '}') {
            throw new IllegalArgumentException("invalid cache envelope");
        }
        String payload = raw.substring(dataMarker + 7, raw.length() - 1);
        return new CachedErrand(payload, logicalExpireAt, false);
    }

    private long numberField(String json, String field) {
        String marker = "\"" + field + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("missing cache field: " + field);
        }
        start += marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }
}
