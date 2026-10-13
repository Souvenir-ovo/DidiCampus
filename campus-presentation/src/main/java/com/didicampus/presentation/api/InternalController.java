package com.didicampus.presentation.api;

import com.didicampus.application.usecase.CacheConsistencyCheckService;
import com.didicampus.application.usecase.GetErrandDetailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 本地或内网运维使用的观测接口。
 *
 * <p>生产部署时应由网关限制 {@code /api/internal/**} 的外部访问。</p>
 */
@RestController
@RequestMapping("/api/internal")
public class InternalController {

    private final GetErrandDetailService detailService;
    private final CacheConsistencyCheckService consistencyService;

    public InternalController(GetErrandDetailService detailService,
                              CacheConsistencyCheckService consistencyService) {
        this.detailService = detailService;
        this.consistencyService = consistencyService;
    }

    @GetMapping("/cache-stats")
    public ApiResponse<Map<String, Object>> cacheStats() {
        return ApiResponse.ok(Map.of(
                "requests", detailService.requestCount(),
                "cacheHits", detailService.cacheHitCount(),
                "dbLoads", detailService.dbLoadCount(),
                "hitRate", detailService.hitRate()));
    }

    @PostMapping("/cache-stats/reset")
    public ApiResponse<Void> resetCacheStats() {
        detailService.resetStats();
        return ApiResponse.ok();
    }

    @PostMapping("/cache-check")
    public ApiResponse<Map<String, Integer>> cacheCheck() {
        return ApiResponse.ok(Map.of("diffs", consistencyService.checkOnce()));
    }
}
