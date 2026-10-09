package com.didicampus.worker;

import com.didicampus.application.usecase.CreditCalibrationService;
import com.didicampus.domain.credit.model.CreditEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CreditCalibrationJob {

    private static final Logger log = LoggerFactory.getLogger(CreditCalibrationJob.class);

    private final CreditCalibrationService creditCalibrationService;
    private final int batchSize;

    public CreditCalibrationJob(CreditCalibrationService creditCalibrationService,
                                @Value("${didicampus.worker.credit.batch-size:1000}") int batchSize) {
        this.creditCalibrationService = creditCalibrationService;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(cron = "${didicampus.worker.credit.cron:0 0 3 * * ?}")
    public void calibrate() {
        int changed = creditCalibrationService.calibrate(CreditEventType.WINDOW_DAYS, batchSize);
        if (changed > 0) {
            log.info("credit calibration finished, changed={}", changed);
        }
    }
}
