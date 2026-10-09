package com.didicampus.presentation.api;

import com.didicampus.domain.credit.model.CreditEvent;
import com.didicampus.domain.credit.model.CreditScore;
import com.didicampus.domain.credit.ports.CreditRankingPort;
import com.didicampus.domain.credit.ports.CreditRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/credits")
public class CreditController {

    private final CreditRepository creditRepository;
    private final CreditRankingPort rankingPort;

    public CreditController(CreditRepository creditRepository,
                            CreditRankingPort rankingPort) {
        this.creditRepository = creditRepository;
        this.rankingPort = rankingPort;
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<CreditView> score(@PathVariable long userId) {
        CreditScore score = creditRepository.find(userId)
                .orElseGet(() -> CreditScore.initial(userId));
        return ApiResponse.ok(CreditView.from(score));
    }

    @GetMapping("/users/{userId}/events")
    public ApiResponse<List<CreditEventView>> recentEvents(@PathVariable long userId,
                                                           @RequestParam(defaultValue = "30") int days,
                                                           @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(creditRepository.recentEvents(userId, days, limit)
                .stream()
                .map(CreditEventView::from)
                .toList());
    }

    @GetMapping("/campuses/{campusId}/ranking")
    public ApiResponse<List<CreditRankingPort.Entry>> ranking(@PathVariable long campusId,
                                                              @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(rankingPort.top(campusId, limit));
    }

    public record CreditView(long userId, int score, long version) {
        static CreditView from(CreditScore score) {
            return new CreditView(score.userId(), score.score(), score.version());
        }
    }

    public record CreditEventView(long id,
                                  String bizNo,
                                  long userId,
                                  String type,
                                  int delta,
                                  String refType,
                                  long refId) {
        static CreditEventView from(CreditEvent event) {
            return new CreditEventView(
                    event.id(),
                    event.bizNo(),
                    event.userId(),
                    event.type().name(),
                    event.delta(),
                    event.refType(),
                    event.refId());
        }
    }
}
