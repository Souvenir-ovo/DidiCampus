package com.didicampus.worker;

import com.didicampus.application.usecase.TimeoutTransferService;
import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.ports.ErrandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TimeoutScanJob {

    private static final Logger log = LoggerFactory.getLogger(TimeoutScanJob.class);

    private final ErrandRepository errandRepository;
    private final TimeoutTransferService timeoutTransferService;
    private final long graceSeconds;
    private final int batchSize;

    public TimeoutScanJob(ErrandRepository errandRepository,
                          TimeoutTransferService timeoutTransferService,
                          @Value("${didicampus.worker.timeout.grace-seconds:2}") long graceSeconds,
                          @Value("${didicampus.worker.timeout.batch-size:200}") int batchSize) {
        this.errandRepository = errandRepository;
        this.timeoutTransferService = timeoutTransferService;
        this.graceSeconds = Math.max(0L, graceSeconds);
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${didicampus.worker.timeout.interval-ms:5000}")
    public void scanConfirmTimeouts() {
        long timeoutSeconds = timeoutTransferService.confirmationTimeoutSeconds() + graceSeconds;
        List<Errand> errands = errandRepository.findConfirmTimeout(timeoutSeconds, batchSize);
        if (errands.isEmpty()) {
            return;
        }

        int transferred = 0;
        int reopened = 0;
        int skipped = 0;
        for (Errand errand : errands) {
            try {
                TimeoutTransferService.Outcome outcome =
                        timeoutTransferService.handleTimeout(errand.id(), errand.round());
                switch (outcome) {
                    case TRANSFERRED -> transferred++;
                    case REOPENED -> reopened++;
                    case SKIPPED -> skipped++;
                }
            } catch (RuntimeException ex) {
                skipped++;
                log.warn("confirm timeout scan failed, errandId={}", errand.id(), ex);
            }
        }
        log.info("confirm timeout scan finished, scanned={}, transferred={}, reopened={}, skipped={}",
                errands.size(), transferred, reopened, skipped);
    }
}
