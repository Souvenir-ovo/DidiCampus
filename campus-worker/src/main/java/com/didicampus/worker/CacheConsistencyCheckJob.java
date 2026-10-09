package com.didicampus.worker;

import com.didicampus.application.usecase.CacheConsistencyCheckService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CacheConsistencyCheckJob {

    private final CacheConsistencyCheckService cacheConsistencyCheckService;

    public CacheConsistencyCheckJob(CacheConsistencyCheckService cacheConsistencyCheckService) {
        this.cacheConsistencyCheckService = cacheConsistencyCheckService;
    }

    @Scheduled(fixedDelayString = "${didicampus.worker.cache-check.interval-ms:300000}")
    public void check() {
        cacheConsistencyCheckService.checkOnce();
    }
}
