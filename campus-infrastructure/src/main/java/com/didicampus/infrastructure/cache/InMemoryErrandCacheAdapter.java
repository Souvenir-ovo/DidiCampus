package com.didicampus.infrastructure.cache;

import com.didicampus.domain.errand.ports.ErrandCachePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(name = "didicampus.cache.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryErrandCacheAdapter implements ErrandCachePort {

    private final ConcurrentMap<Long, CachedErrand> cache = new ConcurrentHashMap<>();
    private final Set<Long> knownErrands = ConcurrentHashMap.newKeySet();
    private final Set<Long> rebuildLocks = ConcurrentHashMap.newKeySet();
    private final long logicalTtlMillis;
    private final long emptyTtlMillis;

    public InMemoryErrandCacheAdapter(
            @Value("${didicampus.cache.logical-ttl-ms:300000}") long logicalTtlMillis,
            @Value("${didicampus.cache.empty-ttl-ms:60000}") long emptyTtlMillis) {
        this.logicalTtlMillis = Math.max(1000L, logicalTtlMillis);
        this.emptyTtlMillis = Math.max(1000L, emptyTtlMillis);
    }

    @Override
    public Optional<CachedErrand> get(long errandId) {
        CachedErrand cached = cache.get(errandId);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.logicallyExpired() && cached.isEmpty()) {
            cache.remove(errandId, cached);
            return Optional.empty();
        }
        return Optional.of(cached);
    }

    @Override
    public void put(long errandId, String payloadJson) {
        registerExisting(errandId);
        cache.put(errandId, new CachedErrand(
                payloadJson,
                System.currentTimeMillis() + logicalTtlMillis,
                false));
    }

    @Override
    public void putEmpty(long errandId) {
        cache.put(errandId, new CachedErrand(
                "{}",
                System.currentTimeMillis() + emptyTtlMillis,
                true));
    }

    @Override
    public void evict(long errandId) {
        cache.remove(errandId);
    }

    @Override
    public boolean tryAcquireRebuild(long errandId) {
        return rebuildLocks.add(errandId);
    }

    @Override
    public void releaseRebuild(long errandId) {
        rebuildLocks.remove(errandId);
    }

    @Override
    public boolean mightExist(long errandId) {
        return knownErrands.isEmpty() || knownErrands.contains(errandId);
    }

    @Override
    public void registerExisting(long errandId) {
        knownErrands.add(errandId);
    }
}
