package com.didicampus.infrastructure.cache;

import com.didicampus.domain.errand.ports.CacheEvictDelayPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "didicampus.mq.enabled", havingValue = "false", matchIfMissing = true)
public class NoopCacheEvictDelayAdapter implements CacheEvictDelayPort {

    @Override
    public void scheduleEvict(long errandId, Instant deliverAt) {
        // MQ 未启用时只做事务提交后的第一次删除，第二次延迟删除交给 TTL 兜底。
    }
}
