package com.didicampus.domain.errand.ports;

import java.util.Optional;

/**
 * 任务详情缓存端口。领域层不暴露 Redis、分片 key 和 TTL 等实现细节。
 */
public interface ErrandCachePort {

    Optional<CachedErrand> get(long errandId);

    void put(long errandId, String payloadJson);

    void putEmpty(long errandId);

    void evict(long errandId);

    boolean tryAcquireRebuild(long errandId);

    void releaseRebuild(long errandId);

    boolean mightExist(long errandId);

    void registerExisting(long errandId);

    record CachedErrand(String payloadJson, long logicalExpireAt, boolean isEmpty) {
        public boolean logicallyExpired() {
            return System.currentTimeMillis() > logicalExpireAt;
        }
    }
}
