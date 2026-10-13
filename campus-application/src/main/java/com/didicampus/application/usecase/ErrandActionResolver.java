package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.ErrandStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据任务状态和当前查看者身份计算可执行动作。
 *
 * <p>状态流转仍然由领域状态机负责，本类只叠加身份条件，
 * 避免前端再维护一份容易漂移的业务规则。</p>
 */
@Component
public class ErrandActionResolver {

    private final long arbitratorId;

    public ErrandActionResolver(
            @Value("${didicampus.auth.arbitrator-id:9001}") long arbitratorId) {
        this.arbitratorId = arbitratorId;
    }

    public Resolved resolve(ErrandDetail detail, long viewerId) {
        ErrandStatus status = ErrandStatus.valueOf(detail.status());
        boolean publisher = detail.publisherId() == viewerId;
        boolean currentRunner = detail.runnerId() != null && detail.runnerId() == viewerId;
        boolean arbitrator = viewerId == arbitratorId;

        List<String> actions = new ArrayList<>();
        if (status.grabbable() && !publisher) {
            actions.add("GRAB");
        }
        if (status == ErrandStatus.LOCKED && currentRunner) {
            actions.add("CONFIRM");
        }
        if (status == ErrandStatus.ACCEPTED && currentRunner) {
            actions.add("PICKUP");
        }
        if (status == ErrandStatus.PICKED_UP && currentRunner) {
            actions.add("DELIVER");
        }
        if (status == ErrandStatus.DELIVERED
                && publisher
                && status.canTransitTo(ErrandStatus.SETTLED)) {
            actions.add("SETTLE");
        }
        if (publisher && (status == ErrandStatus.DRAFT || status == ErrandStatus.PUBLISHED)) {
            actions.add("CANCEL");
        }
        if ((publisher || currentRunner)
                && status.canTransitTo(ErrandStatus.DISPUTED)) {
            actions.add("DISPUTE");
        }
        if (arbitrator && status == ErrandStatus.DISPUTED) {
            actions.add("ARBITRATE");
        }

        String role = arbitrator ? "ARBITRATOR"
                : publisher ? "PUBLISHER"
                : currentRunner ? "CURRENT_RUNNER"
                : "VIEWER";
        return new Resolved(List.copyOf(actions), role);
    }

    public record Resolved(List<String> actions, String role) {
    }
}
