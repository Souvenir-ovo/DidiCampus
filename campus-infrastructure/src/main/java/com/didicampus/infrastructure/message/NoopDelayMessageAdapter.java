package com.didicampus.infrastructure.message;

import com.didicampus.domain.errand.ports.DelayMessagePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "didicampus.mq.enabled", havingValue = "false", matchIfMissing = true)
public class NoopDelayMessageAdapter implements DelayMessagePort {

    private static final Logger log = LoggerFactory.getLogger(NoopDelayMessageAdapter.class);

    @Override
    public void send(String topic, String messageKey, String payload, Instant deliverAt) {
        log.debug("mq disabled, delay message stays in local_message, key={}, deliverAt={}",
                messageKey, deliverAt);
    }
}
