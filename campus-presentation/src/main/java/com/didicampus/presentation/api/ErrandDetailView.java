package com.didicampus.presentation.api;

import com.didicampus.application.usecase.ErrandActionResolver;
import com.didicampus.application.usecase.ErrandDetail;

import java.time.Instant;
import java.util.List;

/**
 * 详情接口响应。公共任务字段来自缓存，角色和动作由当前请求实时计算。
 */
public record ErrandDetailView(long id,
                               long campusId,
                               long publisherId,
                               Long runnerId,
                               String type,
                               String title,
                               long rewardCents,
                               int slotTotal,
                               int slotTaken,
                               String status,
                               int round,
                               long version,
                               Instant lockedAt,
                               Instant deliveredAt,
                               String role,
                               List<String> availableActions) {

    public static ErrandDetailView from(ErrandDetail detail,
                                         ErrandActionResolver.Resolved resolved) {
        return new ErrandDetailView(
                detail.id(),
                detail.campusId(),
                detail.publisherId(),
                detail.runnerId(),
                detail.type(),
                detail.title(),
                detail.rewardCents(),
                detail.slotTotal(),
                detail.slotTaken(),
                detail.status(),
                detail.round(),
                detail.version(),
                detail.lockedAt(),
                detail.deliveredAt(),
                resolved.role(),
                resolved.actions());
    }
}
