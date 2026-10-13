package com.didicampus.application.usecase;

import com.didicampus.domain.credit.model.CreditEvent;
import com.didicampus.domain.credit.model.CreditEventType;
import com.didicampus.domain.credit.model.CreditScore;
import com.didicampus.domain.credit.ports.CreditRankingPort;
import com.didicampus.domain.credit.ports.CreditRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QueryCreditService {

    private static final int MAX_RANKING_SIZE = 100;
    private static final int EVENT_PAGE_SIZE = 50;

    private final CreditRepository creditRepository;
    private final CreditRankingPort rankingPort;

    public QueryCreditService(CreditRepository creditRepository,
                              CreditRankingPort rankingPort) {
        this.creditRepository = creditRepository;
        this.rankingPort = rankingPort;
    }

    public CreditScore score(long userId) {
        return creditRepository.find(userId)
                .orElseGet(() -> CreditScore.initial(userId));
    }

    public List<CreditEvent> recentEvents(long userId, int days, int limit) {
        int safeDays = Math.min(Math.max(days, 1), CreditEventType.WINDOW_DAYS);
        int safeLimit = Math.min(Math.max(limit, 1), EVENT_PAGE_SIZE);
        return creditRepository.recentEvents(userId, safeDays, safeLimit);
    }

    public List<CreditRankingPort.Entry> ranking(long campusId, int limit) {
        return rankingPort.top(
                campusId,
                Math.min(Math.max(limit, 1), MAX_RANKING_SIZE));
    }
}
