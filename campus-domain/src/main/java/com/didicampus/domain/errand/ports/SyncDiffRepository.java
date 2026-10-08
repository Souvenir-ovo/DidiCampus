package com.didicampus.domain.errand.ports;

import java.time.Instant;

public interface SyncDiffRepository {

    void record(Instant checkTime, long errandId, String field,
                String dbValue, String cacheValue, boolean fixed);

    long countSince(Instant since);
}
