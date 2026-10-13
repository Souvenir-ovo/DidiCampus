package com.didicampus.infrastructure.message;

import com.didicampus.domain.errand.ports.DelayMessagePort;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "didicampus.mq.enabled", havingValue = "true")
public class RocketMqDelayMessageAdapter implements DelayMessagePort {

    private static final Logger log = LoggerFactory.getLogger(RocketMqDelayMessageAdapter.class);

    private final ClientServiceProvider provider;
    private final Producer producer;

    public RocketMqDelayMessageAdapter(ClientServiceProvider provider,
                                       @Qualifier("rocketMqDelayProducer") Producer producer) {
        this.provider = provider;
        this.producer = producer;
    }

    @Override
    public void send(String topic, String messageKey, String payload, Instant deliverAt) {
        try {
            Message message = provider.newMessageBuilder()
                    .setTopic(topic)
                    .setKeys(messageKey)
                    .setBody(payload.getBytes(StandardCharsets.UTF_8))
                    .setDeliveryTimestamp(deliverAt.toEpochMilli())
                    .build();
            var receipt = producer.send(message);
            log.debug("rocketmq delayed message sent, key={}, topic={}, msgId={}",
                    messageKey, topic, receipt.getMessageId());
        } catch (Exception ex) {
            throw new IllegalStateException("send delayed rocketmq message failed, key=" + messageKey, ex);
        }
    }
}
