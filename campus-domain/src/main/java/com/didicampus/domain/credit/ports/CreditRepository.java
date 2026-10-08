package com.didicampus.domain.credit.ports;

import com.didicampus.domain.credit.model.CreditEvent;
import com.didicampus.domain.credit.model.CreditScore;

import java.util.List;
import java.util.Optional;

public interface CreditRepository {

    int scoreOf(long userId);

    Optional<CreditScore> find(long userId);

    boolean applyEvent(CreditEvent event);

    List<CreditEvent> recentEvents(long userId, int days, int limit);

    int windowDelta(long userId, int windowDays);

    int calibrateScores(int windowDays, int limit);
}
