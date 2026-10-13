package com.didicampus.worker;

import com.didicampus.domain.notify.ports.NotificationRepository;
import com.didicampus.infrastructure.message.RocketMqFundEventAdapter;
import com.didicampus.shared.SnowflakeIdGenerator;
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
public class FundEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(FundEventConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final NotificationRepository notificationRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final String topic;
    private final String group;
    private PushConsumer consumer;

    public FundEventConsumer(ClientServiceProvider provider,
                             ClientConfiguration configuration,
                             NotificationRepository notificationRepository,
                             SnowflakeIdGenerator idGenerator,
                             @Value("${didicampus.mq.topic.fund-event:"
                                     + RocketMqFundEventAdapter.TOPIC_FUND_EVENT + "}") String topic,
                             @Value("${didicampus.mq.group.fund-event:"
                                     + "didicampus-fund-event-consumer}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.notificationRepository = notificationRepository;
        this.idGenerator = idGenerator;
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
                .setMessageListener(message -> {
                    String body = StandardCharsets.UTF_8.decode(message.getBody()).toString();
                    String messageKey = message.getKeys().stream().findFirst()
                            .orElse(message.getMessageId().toString());
                    String eventType = message.getTag().orElse("UNKNOWN");
                    return handleEvent(body, messageKey, eventType);
                })
                .build();
        log.info("fund event consumer started, topic={}, group={}", topic, group);
    }

    @PreDestroy
    public void stop() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
    }

    ConsumeResult handleEvent(String body, String messageKey, String eventType) {
        try {
            long errandId = MessageJson.longValue(body, "errandId");
            long publisherId = MessageJson.longValue(body, "publisherId");
            long runnerId = MessageJson.longValue(body, "runnerId");
            long amountCents = MessageJson.longValue(body, "amountCents");

            notifyPublisher(messageKey, eventType, publisherId, errandId, amountCents);
            if (runnerId > 0 && shouldNotifyRunner(eventType)) {
                notifyRunner(messageKey, eventType, runnerId, errandId, amountCents);
            }

            log.info("fund event consumed, key={}, type={}, errandId={}",
                    messageKey, eventType, errandId);
            return ConsumeResult.SUCCESS;
        } catch (IllegalArgumentException ex) {
            log.warn("bad fund event message ignored, key={}, body={}", messageKey, body, ex);
            return ConsumeResult.SUCCESS;
        } catch (RuntimeException ex) {
            log.error("fund event consume failed, key={}, body={}", messageKey, body, ex);
            return ConsumeResult.FAILURE;
        }
    }

    private void notifyPublisher(String messageKey, String eventType, long userId,
                                 long errandId, long amountCents) {
        notificationRepository.insertIfAbsent(
                idGenerator.nextId(),
                messageKey + ":publisher",
                userId,
                errandId,
                eventType,
                publisherText(eventType, errandId, amountCents));
    }

    private void notifyRunner(String messageKey, String eventType, long userId,
                              long errandId, long amountCents) {
        notificationRepository.insertIfAbsent(
                idGenerator.nextId(),
                messageKey + ":runner",
                userId,
                errandId,
                eventType,
                runnerText(eventType, errandId, amountCents));
    }

    private boolean shouldNotifyRunner(String eventType) {
        return "SETTLED".equals(eventType) || "ARBITRATED".equals(eventType);
    }

    private String publisherText(String eventType, long errandId, long amountCents) {
        return switch (eventType) {
            case "SETTLED" -> "任务 " + errandId + " 已完成结算，支付金额 " + amountCents + " 分";
            case "REFUNDED" -> "任务 " + errandId + " 已退款，退回金额 " + amountCents + " 分";
            case "ARBITRATED" -> "任务 " + errandId + " 仲裁已完成，处理金额 " + amountCents + " 分";
            default -> "任务 " + errandId + " 资金状态发生变更";
        };
    }

    private String runnerText(String eventType, long errandId, long amountCents) {
        return switch (eventType) {
            case "SETTLED" -> "任务 " + errandId + " 结算完成，入账金额 " + amountCents + " 分";
            case "ARBITRATED" -> "任务 " + errandId + " 仲裁结算完成，入账金额 " + amountCents + " 分";
            default -> "任务 " + errandId + " 资金状态发生变更";
        };
    }
}
