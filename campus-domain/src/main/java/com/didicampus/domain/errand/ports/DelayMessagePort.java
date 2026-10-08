package com.didicampus.domain.errand.ports;

import java.time.Instant;

public interface DelayMessagePort {

    void send(String topic, String messageKey, String payload, Instant deliverAt);
}
