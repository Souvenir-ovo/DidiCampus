package com.didicampus.worker;

import com.didicampus.domain.errand.ports.CacheEvictDelayPort;
import com.didicampus.domain.errand.ports.ErrandCachePort;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "didicampus.mq.enabled", havingValue = "true")
public class CacheEvictConsumer {

    private static final Logger log = LoggerFactory.getLogger(CacheEvictConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final ErrandCachePort cachePort;
    private final String group;
    private PushConsumer consumer;

    public CacheEvictConsumer(ClientServiceProvider provider,
                              ClientConfiguration configuration,
                              ErrandCachePort cachePort,
                              @Value("${didicampus.mq.group.cache-evict:"
                                      + "didicampus-cache-evict-consumer}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.cachePort = cachePort;
        this.group = group;
    }

    @PostConstruct
    public void start() throws Exception {
        consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(group)
                .setSubscriptionExpressions(Map.of(CacheEvictDelayPort.TOPIC_CACHE_EVICT,
                        new FilterExpression("*", FilterExpressionType.TAG)))
                .setMessageListener(message -> handleBody(
                        StandardCharsets.UTF_8.decode(message.getBody()).toString()))
                .build();
        log.info("cache evict consumer started, topic={}, group={}",
                CacheEvictDelayPort.TOPIC_CACHE_EVICT, group);
    }

    @PreDestroy
    public void stop() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
    }

    ConsumeResult handleBody(String body) {
        try {
            long errandId = MessageJson.longValue(body, "errandId");
            cachePort.evict(errandId);
            log.debug("delayed cache eviction consumed, errandId={}", errandId);
            return ConsumeResult.SUCCESS;
        } catch (IllegalArgumentException ex) {
            log.warn("bad cache evict message ignored, body={}", body, ex);
            return ConsumeResult.SUCCESS;
        } catch (RuntimeException ex) {
            log.error("cache evict message consume failed, body={}", body, ex);
            return ConsumeResult.FAILURE;
        }
    }
}
