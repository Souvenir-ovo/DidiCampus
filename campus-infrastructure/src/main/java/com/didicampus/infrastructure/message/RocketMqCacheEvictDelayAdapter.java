package com.didicampus.infrastructure.message;

import com.didicampus.domain.errand.ports.CacheEvictDelayPort;
import com.didicampus.domain.errand.ports.DelayMessagePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "didicampus.mq.enabled", havingValue = "true")
public class RocketMqCacheEvictDelayAdapter implements CacheEvictDelayPort {

    private final DelayMessagePort delayMessagePort;

    public RocketMqCacheEvictDelayAdapter(DelayMessagePort delayMessagePort) {
        this.delayMessagePort = delayMessagePort;
    }

    @Override
    public void scheduleEvict(long errandId, Instant deliverAt) {
        String key = "cache-evict:" + errandId + ":" + deliverAt.toEpochMilli();
        String payload = "{\"errandId\":%d}".formatted(errandId);
        delayMessagePort.send(TOPIC_CACHE_EVICT, key, payload, deliverAt);
    }
}
