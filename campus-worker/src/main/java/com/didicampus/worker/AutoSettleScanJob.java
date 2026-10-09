package com.didicampus.worker;

import com.didicampus.application.usecase.SettleErrandService;
import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.ports.ErrandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AutoSettleScanJob {

    private static final Logger log = LoggerFactory.getLogger(AutoSettleScanJob.class);

    private final ErrandRepository errandRepository;
    private final SettleErrandService settleErrandService;
    private final long autoSettleSeconds;
    private final int batchSize;

    public AutoSettleScanJob(ErrandRepository errandRepository,
                             SettleErrandService settleErrandService,
                             @Value("${didicampus.worker.settle.auto-seconds:86400}") long autoSettleSeconds,
                             @Value("${didicampus.worker.settle.batch-size:200}") int batchSize) {
        this.errandRepository = errandRepository;
        this.settleErrandService = settleErrandService;
        this.autoSettleSeconds = Math.max(1L, autoSettleSeconds);
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${didicampus.worker.settle.interval-ms:5000}")
    public void scanAutoSettle() {
        List<Errand> dueErrands = errandRepository.findAutoSettleDue(autoSettleSeconds, batchSize);
        if (dueErrands.isEmpty()) {
            return;
        }

        int settled = 0;
        for (Errand errand : dueErrands) {
            try {
                SettleErrandService.Result result =
                        settleErrandService.settle(errand.id(), Errand.SYSTEM_OPERATOR);
                if (result == SettleErrandService.Result.SETTLED
                        || result == SettleErrandService.Result.ALREADY_SETTLED) {
                    settled++;
                }
            } catch (RuntimeException ex) {
                log.warn("auto settle scan failed, errandId={}", errand.id(), ex);
            }
        }
        log.info("auto settle scan finished, scanned={}, settled={}", dueErrands.size(), settled);
    }
}
