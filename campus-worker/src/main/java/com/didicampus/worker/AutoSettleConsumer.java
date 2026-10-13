package com.didicampus.worker;

import com.didicampus.application.usecase.DelayTaskPolicy;
import com.didicampus.application.usecase.SettleErrandService;
import com.didicampus.domain.errand.model.Errand;
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
public class AutoSettleConsumer {

    private static final Logger log = LoggerFactory.getLogger(AutoSettleConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final SettleErrandService settleErrandService;
    private final String topic;
    private final String group;
    private PushConsumer consumer;

    public AutoSettleConsumer(ClientServiceProvider provider,
                              ClientConfiguration configuration,
                              SettleErrandService settleErrandService,
                              @Value("${didicampus.mq.topic.auto-settle:"
                                      + DelayTaskPolicy.AUTO_SETTLE_TOPIC + "}") String topic,
                              @Value("${didicampus.mq.group.auto-settle:"
                                      + "didicampus-auto-settle-consumer}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.settleErrandService = settleErrandService;
        this.topic = topic;
        this.group = group;
    }

    @PostConstruct
    public void start() throws Exception {
        consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(group)
                .setSubscriptionExpressions(Map.of(topic,
                        new FilterExpression("*", FilterExpressionType.TAG)))
                .setMessageListener(message -> handleBody(
                        StandardCharsets.UTF_8.decode(message.getBody()).toString()))
                .build();
        log.info("auto settle consumer started, topic={}, group={}", topic, group);
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
            SettleErrandService.Result result =
                    settleErrandService.settle(errandId, Errand.SYSTEM_OPERATOR);
            log.info("auto settle message consumed, errandId={}, result={}", errandId, result);
            return ConsumeResult.SUCCESS;
        } catch (IllegalArgumentException ex) {
            log.warn("bad auto settle message ignored, body={}", body, ex);
            return ConsumeResult.SUCCESS;
        } catch (RuntimeException ex) {
            log.error("auto settle message consume failed, body={}", body, ex);
            return ConsumeResult.FAILURE;
        }
    }
}
