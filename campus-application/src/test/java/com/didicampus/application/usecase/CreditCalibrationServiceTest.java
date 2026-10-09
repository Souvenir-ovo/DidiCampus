package com.didicampus.application.usecase;

import com.didicampus.domain.credit.model.CreditEvent;
import com.didicampus.domain.credit.model.CreditScore;
import com.didicampus.domain.credit.ports.CreditRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreditCalibrationServiceTest {

    @Test
    void calibrateNormalizesInvalidArguments() {
        FakeCredits credits = new FakeCredits();
        CreditCalibrationService service = new CreditCalibrationService(credits);

        int changed = service.calibrate(0, -5);

        assertEquals(7, changed);
        assertEquals(1, credits.windowDays);
        assertEquals(1, credits.limit);
    }

    private static final class FakeCredits implements CreditRepository {
        private int windowDays;
        private int limit;

        @Override public int scoreOf(long userId) { return 0; }
        @Override public Optional<CreditScore> find(long userId) { return Optional.empty(); }
        @Override public boolean applyEvent(CreditEvent event) { return false; }
        @Override public List<CreditEvent> recentEvents(long userId, int days, int limit) { return List.of(); }
        @Override public int windowDelta(long userId, int windowDays) { return 0; }

        @Override
        public int calibrateScores(int windowDays, int limit) {
            this.windowDays = windowDays;
            this.limit = limit;
            return 7;
        }
    }
}
