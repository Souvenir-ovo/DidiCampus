package com.didicampus.domain.errand.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * 跑腿任务的生命周期状态。
 *
 * <p>状态关系集中维护在这里，聚合和应用服务不再各自维护一套 if/else。
 * LOCKED 到 LOCKED 是有意保留的自环，代表确认超时后更换候选跑腿员。</p>
 */
public enum ErrandStatus {

    DRAFT,
    PUBLISHED,
    LOCKED,
    ACCEPTED,
    PICKED_UP,
    DELIVERED,
    SETTLED,
    CLOSED,
    CANCELLED,
    DISPUTED,
    REFUNDED;

    public Set<ErrandStatus> allowedTargets() {
        return switch (this) {
            case DRAFT -> Set.of(PUBLISHED, CANCELLED);
            case PUBLISHED -> Set.of(LOCKED, CANCELLED);
            case LOCKED -> Set.of(ACCEPTED, LOCKED, PUBLISHED, CANCELLED);
            case ACCEPTED -> Set.of(PICKED_UP, DISPUTED, CANCELLED);
            case PICKED_UP -> Set.of(DELIVERED, DISPUTED);
            case DELIVERED -> Set.of(SETTLED, DISPUTED);
            case DISPUTED -> Set.of(SETTLED, REFUNDED);
            case SETTLED, REFUNDED -> Set.of(CLOSED);
            case CLOSED, CANCELLED -> Set.of();
        };
    }

    public boolean canTransitTo(ErrandStatus target) {
        return target != null && allowedTargets().contains(target);
    }

    public boolean grabbable() {
        return this == PUBLISHED;
    }
}
