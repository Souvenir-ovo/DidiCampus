package com.didicampus.domain.grab.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 抢单事实记录。数据库会使用任务、轮次、名额以及用户组合唯一索引兜底。
 */
public record GrabRecord(long id, long campusId, long errandId, long runnerId,
                         int seq, int round, GrabResultType result, Instant createdAt) {

    public GrabRecord {
        if (seq < 0 || round < 0) {
            throw new IllegalArgumentException("seq 和 round 不能为负数");
        }
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static GrabRecord grabbed(long id, long campusId, long errandId,
                                     long runnerId, int seq, int round) {
        return new GrabRecord(id, campusId, errandId, runnerId, seq, round,
                GrabResultType.GRABBED, Instant.now());
    }

    public static GrabRecord candidate(long id, long campusId, long errandId,
                                       long runnerId, int round) {
        return new GrabRecord(id, campusId, errandId, runnerId, 0, round,
                GrabResultType.CANDIDATE, Instant.now());
    }

    public enum GrabResultType {
        GRABBED,
        CANDIDATE,
        EXPIRED
    }
}
