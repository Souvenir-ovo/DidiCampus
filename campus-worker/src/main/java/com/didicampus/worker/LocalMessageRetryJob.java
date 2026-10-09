package com.didicampus.worker;

import com.didicampus.domain.errand.ports.DelayMessagePort;
import com.didicampus.domain.errand.ports.LocalMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LocalMessageRetryJob {

    private static final Logger log = LoggerFactory.getLogger(LocalMessageRetryJob.class);

    private final LocalMessageRepository localMessageRepository;
    private final DelayMessagePort delayMessagePort;
    private final int batchSize;
    private final int maxRetry;

    public LocalMessageRetryJob(LocalMessageRepository localMessageRepository,
                                DelayMessagePort delayMessagePort,
                                @Value("${didicampus.worker.local-message.batch-size:100}") int batchSize,
                                @Value("${didicampus.worker.local-message.max-retry:6}") int maxRetry) {
        this.localMessageRepository = localMessageRepository;
        this.delayMessagePort = delayMessagePort;
        this.batchSize = Math.max(1, batchSize);
        this.maxRetry = Math.max(1, maxRetry);
    }

    @Scheduled(fixedDelayString = "${didicampus.worker.local-message.interval-ms:10000}")
    public void retryPendingMessages() {
        List<LocalMessageRepository.PendingMessage> messages =
                localMessageRepository.findPending(batchSize);
        if (messages.isEmpty()) {
            return;
        }

        int sent = 0;
        for (LocalMessageRepository.PendingMessage message : messages) {
            try {
                delayMessagePort.send(
                        message.topic(),
                        message.messageKey(),
                        message.payload(),
                        message.deliverAt());
                localMessageRepository.markSent(message.messageKey());
                sent++;
            } catch (RuntimeException ex) {
                localMessageRepository.markRetry(message.messageKey(), maxRetry);
                log.warn("local message retry failed, key={}, retry={}",
                        message.messageKey(), message.retryCount(), ex);
            }
        }
        log.info("local message retry finished, scanned={}, sent={}", messages.size(), sent);
    }
}
