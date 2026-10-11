package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;

import java.time.Instant;

/**
 * 任务详情的共享读模型。
 *
 * <p>它只包含任务自身的数据，适合放入跨用户共享的缓存。
 * 例如“当前查看者能执行哪些操作”属于请求上下文，不能放进这个对象。</p>
 */
public record ErrandDetail(long id,
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
                           Instant deliveredAt) {

    public static ErrandDetail from(Errand errand) {
        return new ErrandDetail(
                errand.id(),
                errand.campusId(),
                errand.publisherId(),
                errand.grabberId(),
                errand.type().name(),
                errand.title(),
                errand.reward().cents(),
                errand.slotTotal(),
                errand.slotTaken(),
                errand.status().name(),
                errand.round(),
                errand.version(),
                errand.lockedAt(),
                errand.deliveredAt());
    }
}
