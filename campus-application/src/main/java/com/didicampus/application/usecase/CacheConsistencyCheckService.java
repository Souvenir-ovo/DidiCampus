package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.ports.ErrandCachePort;
import com.didicampus.domain.errand.ports.ErrandQueryPort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.errand.ports.SyncDiffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class CacheConsistencyCheckService {

    private static final Logger log = LoggerFactory.getLogger(CacheConsistencyCheckService.class);

    private final ErrandQueryPort errandQueryPort;
    private final ErrandRepository errandRepository;
    private final ErrandCachePort errandCachePort;
    private final SyncDiffRepository syncDiffRepository;
    private final int sampleSize;

    public CacheConsistencyCheckService(ErrandQueryPort errandQueryPort,
                                        ErrandRepository errandRepository,
                                        ErrandCachePort errandCachePort,
                                        SyncDiffRepository syncDiffRepository,
                                        @Value("${didicampus.cache.check-sample-size:100}") int sampleSize) {
        this.errandQueryPort = errandQueryPort;
        this.errandRepository = errandRepository;
        this.errandCachePort = errandCachePort;
        this.syncDiffRepository = syncDiffRepository;
        this.sampleSize = Math.max(1, sampleSize);
    }

    public int checkOnce() {
        List<Long> ids = errandQueryPort.sampleIds(sampleSize);
        Instant checkedAt = Instant.now();
        int diffCount = 0;

        for (Long id : ids) {
            Optional<ErrandCachePort.CachedErrand> cached = errandCachePort.get(id);
            if (cached.isEmpty() || cached.get().isEmpty()) {
                continue;
            }
            Optional<Errand> dbValue = errandRepository.findById(id);
            if (dbValue.isEmpty()) {
                syncDiffRepository.record(checkedAt, id, "existence", "MISSING", "PRESENT", false);
                diffCount++;
                continue;
            }
            diffCount += compareSnapshot(checkedAt, id, dbValue.get(), cached.get().payloadJson());
        }

        if (diffCount > 0) {
            log.warn("cache consistency check found {} diff(s)", diffCount);
        }
        return diffCount;
    }

    private int compareSnapshot(Instant checkedAt, long errandId, Errand dbValue, String cacheJson) {
        int changedFields = 0;
        changedFields += recordIfDifferent(checkedAt, errandId, "status",
                dbValue.status().name(), extractJsonValue(cacheJson, "status"));
        changedFields += recordIfDifferent(checkedAt, errandId, "version",
                String.valueOf(dbValue.version()), extractJsonValue(cacheJson, "version"));
        changedFields += recordIfDifferent(checkedAt, errandId, "reward_amount",
                String.valueOf(dbValue.reward().cents()), extractJsonValue(cacheJson, "rewardCents"));

        if (changedFields > 0) {
            // 删除缓存比直接覆盖更稳妥，下一次读取会走数据库回源。
            errandCachePort.evict(errandId);
        }
        return changedFields;
    }

    private int recordIfDifferent(Instant checkedAt, long errandId, String field,
                                  String dbValue, String cacheValue) {
        if (dbValue.equals(cacheValue)) {
            return 0;
        }
        syncDiffRepository.record(checkedAt, errandId, field, dbValue, cacheValue, true);
        return 1;
    }

    private String extractJsonValue(String json, String field) {
        String textPrefix = "\"" + field + "\":\"";
        int textIndex = json.indexOf(textPrefix);
        if (textIndex >= 0) {
            int start = textIndex + textPrefix.length();
            int end = json.indexOf('"', start);
            return end < 0 ? null : json.substring(start, end);
        }

        String numberPrefix = "\"" + field + "\":";
        int numberIndex = json.indexOf(numberPrefix);
        if (numberIndex < 0) {
            return null;
        }
        int start = numberIndex + numberPrefix.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (!Character.isDigit(c) && c != '-') {
                break;
            }
            end++;
        }
        return json.substring(start, end);
    }
}
