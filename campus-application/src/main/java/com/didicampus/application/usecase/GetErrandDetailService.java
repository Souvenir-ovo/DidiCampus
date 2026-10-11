package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.ports.ErrandCachePort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 任务详情的 Cache Aside 读服务。
 *
 * <p>逻辑过期时不让大量请求排队等待重建锁：拿到锁的请求回源刷新，
 * 其他请求直接返回仍然可读的旧值。任务状态的最终裁决始终依赖数据库 CAS，
 * 因此详情缓存允许存在短暂陈旧。</p>
 */
@Service
public class GetErrandDetailService {

    private static final Logger log = LoggerFactory.getLogger(GetErrandDetailService.class);

    private final ErrandRepository errandRepository;
    private final ErrandCachePort cache;
    private final ObjectMapper objectMapper;

    public GetErrandDetailService(ErrandRepository errandRepository,
                                  ErrandCachePort cache,
                                  ObjectMapper objectMapper) {
        this.errandRepository = errandRepository;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询任务详情。Optional.empty 代表任务不存在，而不是缓存异常。
     */
    public Optional<ErrandDetail> find(long errandId) {
        Optional<ErrandCachePort.CachedErrand> cached = cache.get(errandId);
        if (cached.isPresent()) {
            ErrandCachePort.CachedErrand entry = cached.get();
            if (entry.isEmpty()) {
                return Optional.empty();
            }

            Optional<ErrandDetail> decoded = decode(entry.payloadJson());
            if (decoded.isPresent() && !entry.logicallyExpired()) {
                return decoded;
            }

            if (decoded.isEmpty()) {
                cache.evict(errandId);
            }

            if (cache.tryAcquireRebuild(errandId)) {
                try {
                    return reload(errandId);
                } finally {
                    cache.releaseRebuild(errandId);
                }
            }

            // 无法获得重建权时优先返回旧值，避免热点详情把线程阻塞在锁上。
            return decoded;
        }

        // 布隆过滤器只用于挡住确定不存在的 id，不能作为最终存在性判断。
        if (!cache.mightExist(errandId)) {
            return Optional.empty();
        }
        return reload(errandId);
    }

    private Optional<ErrandDetail> reload(long errandId) {
        Optional<Errand> found = errandRepository.findById(errandId);
        if (found.isEmpty()) {
            cache.putEmpty(errandId);
            return Optional.empty();
        }

        ErrandDetail detail = ErrandDetail.from(found.get());
        try {
            cache.put(errandId, objectMapper.writeValueAsString(detail));
        } catch (JsonProcessingException ex) {
            // 序列化失败不应掩盖已经成功的数据库查询。
            log.error("failed to serialize errand detail, errandId={}", errandId, ex);
        }
        return Optional.of(detail);
    }

    private Optional<ErrandDetail> decode(String payloadJson) {
        try {
            return Optional.of(objectMapper.readValue(payloadJson, ErrandDetail.class));
        } catch (JsonProcessingException | RuntimeException ex) {
            log.warn("invalid errand detail cache payload, evicting entry", ex);
            return Optional.empty();
        }
    }
}
