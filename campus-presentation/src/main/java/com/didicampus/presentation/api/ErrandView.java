package com.didicampus.presentation.api;

import com.didicampus.domain.errand.model.Errand;

import java.time.Instant;

public record ErrandView(long id,
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

    public static ErrandView from(Errand errand) {
        return new ErrandView(
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
