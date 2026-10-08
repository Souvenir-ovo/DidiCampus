package com.didicampus.application.usecase;

import com.didicampus.domain.errand.ports.CacheEvictDelayPort;
import com.didicampus.domain.errand.ports.ErrandCachePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一处理状态写入后的缓存失效。
 *
 * <p>事务提交后删除是正确性基础，延迟再次删除用于覆盖慢读请求回写旧值的窗口。
 * 缓存删除失败不能让已经提交的业务事务重新失败，因此这里只记录失败次数并告警。</p>
 */
@Component
public class CacheEvictSupport {

    private static final Logger log = LoggerFactory.getLogger(CacheEvictSupport.class);

    private final ErrandCachePort cache;
    private final CacheEvictDelayPort delayedEviction;
    private final boolean doubleDeleteEnabled;
    private final long doubleDeleteDelayMillis;
    private final AtomicLong failureCount = new AtomicLong();

    public CacheEvictSupport(ErrandCachePort cache,
                             CacheEvictDelayPort delayedEviction,
                             @Value("${didicampus.cache.double-delete-enabled:true}")
                             boolean doubleDeleteEnabled,
                             @Value("${didicampus.cache.double-delete-ms:500}")
                             long doubleDeleteDelayMillis) {
        this.cache = cache;
        this.delayedEviction = delayedEviction;
        this.doubleDeleteEnabled = doubleDeleteEnabled;
        this.doubleDeleteDelayMillis = doubleDeleteDelayMillis;
    }

    public void evictAfterCommit(long errandId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictNow(errandId);
            scheduleSecondDelete(errandId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictNow(errandId);
                scheduleSecondDelete(errandId);
            }
        });
    }

    public long failureCount() {
        return failureCount.get();
    }

    private void evictNow(long errandId) {
        try {
            cache.evict(errandId);
        } catch (RuntimeException ex) {
            long failures = failureCount.incrementAndGet();
            log.error("errand cache eviction failed, errandId={}, failures={}", errandId, failures, ex);
        }
    }

    private void scheduleSecondDelete(long errandId) {
        if (!doubleDeleteEnabled) {
            return;
        }
        try {
            delayedEviction.scheduleEvict(
                    errandId,
                    Instant.now().plusMillis(doubleDeleteDelayMillis));
        } catch (RuntimeException ex) {
            log.warn("delayed cache eviction registration failed, errandId={}", errandId, ex);
        }
    }
}
