package com.didicampus.presentation.api;

import com.didicampus.application.usecase.QueryCreditService;
import com.didicampus.domain.credit.model.CreditEvent;
import com.didicampus.domain.credit.model.CreditScore;
import com.didicampus.domain.credit.ports.CreditRankingPort;
import com.didicampus.presentation.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/credit")
public class CreditController {

    private final QueryCreditService creditQuery;

    public CreditController(QueryCreditService creditQuery) {
        this.creditQuery = creditQuery;
    }

    @GetMapping("/self")
    public ApiResponse<CreditView> score() {
        return ApiResponse.ok(CreditView.from(creditQuery.score(CurrentUser.id())));
    }

    @GetMapping("/self/events")
    public ApiResponse<List<CreditEventView>> recentEvents(
                                                           @RequestParam(defaultValue = "30") int days,
                                                           @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(creditQuery.recentEvents(CurrentUser.id(), days, limit)
                .stream()
                .map(CreditEventView::from)
                .toList());
    }

    @GetMapping("/ranking")
    public ApiResponse<List<CreditRankingPort.Entry>> ranking(
                                                              @RequestParam(defaultValue = "1") long campusId,
                                                              @RequestParam(defaultValue = "20") int limit) {
        CurrentUser.id();
        return ApiResponse.ok(creditQuery.ranking(campusId, limit));
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
