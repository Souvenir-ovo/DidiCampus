package com.didicampus.domain.errand.ports;

import java.time.Instant;
import java.util.List;

public interface LocalMessageRepository {

    boolean enqueue(long id, String messageKey, String topic,
                    String payload, Instant deliverAt);

    void markSent(String messageKey);

    List<PendingMessage> findPending(int limit);

    void markRetry(String messageKey, int maxRetry);

    record PendingMessage(long id, String messageKey, String topic,
                          String payload, Instant deliverAt, int retryCount) {}
}
