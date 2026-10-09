package com.didicampus.application.usecase;

import com.didicampus.domain.credit.ports.CreditRepository;
import org.springframework.stereotype.Service;

@Service
public class CreditCalibrationService {

    private final CreditRepository creditRepository;

    public CreditCalibrationService(CreditRepository creditRepository) {
        this.creditRepository = creditRepository;
    }

    public int calibrate(int windowDays, int limit) {
        return creditRepository.calibrateScores(Math.max(1, windowDays), Math.max(1, limit));
    }
}
