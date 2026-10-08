package com.didicampus.domain.errand.ports;

import java.time.Instant;

public interface CacheEvictDelayPort {

    String TOPIC_CACHE_EVICT = "errand-cache-evict";

    void scheduleEvict(long errandId, Instant deliverAt);
}
