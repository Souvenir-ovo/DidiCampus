package com.didicampus.worker;

import com.didicampus.application.usecase.DelayTaskPolicy;
import com.didicampus.application.usecase.TimeoutTransferService;
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
public class TimeoutTransferConsumer {

    private static final Logger log = LoggerFactory.getLogger(TimeoutTransferConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final TimeoutTransferService timeoutTransferService;
    private final String topic;
    private final String group;
    private PushConsumer consumer;

    public TimeoutTransferConsumer(ClientServiceProvider provider,
                                   ClientConfiguration configuration,
                                   TimeoutTransferService timeoutTransferService,
                                   @Value("${didicampus.mq.topic.confirm-timeout:"
                                           + DelayTaskPolicy.CONFIRM_TIMEOUT_TOPIC + "}") String topic,
                                   @Value("${didicampus.mq.group.confirm-timeout:"
                                           + "didicampus-timeout-consumer}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.timeoutTransferService = timeoutTransferService;
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
        log.info("timeout transfer consumer started, topic={}, group={}", topic, group);
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
            int round = Math.toIntExact(MessageJson.longValue(body, "round"));
            TimeoutTransferService.Outcome outcome =
                    timeoutTransferService.handleTimeout(errandId, round);
            log.debug("timeout message consumed, errandId={}, round={}, outcome={}",
                    errandId, round, outcome);
            return ConsumeResult.SUCCESS;
        } catch (IllegalArgumentException ex) {
            log.warn("bad timeout message ignored, body={}", body, ex);
            return ConsumeResult.SUCCESS;
        } catch (RuntimeException ex) {
            log.error("timeout message consume failed, body={}", body, ex);
            return ConsumeResult.FAILURE;
        }
    }
}
