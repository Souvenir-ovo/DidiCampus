package com.didicampus.domain.credit.model;

import java.time.Instant;
import java.util.Objects;

public record CreditEvent(long id, String bizNo, long userId,
                          CreditEventType type, int delta,
                          String refType, long refId, Instant createdAt) {

    public CreditEvent {
        if (bizNo == null || bizNo.isBlank()) {
            throw new IllegalArgumentException("信用事件业务号不能为空");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static String settleBizNo(long errandId) {
        return "settle:" + errandId;
    }

    public static String revertBizNo(long errandId, int round) {
        return "revert:" + errandId + ":" + round;
    }

    public static String cancelAfterGrabBizNo(long errandId) {
        return "cancelgrab:" + errandId;
    }

    public static String disputeLoseBizNo(long errandId, long loserId) {
        return "disputelose:" + errandId + ":" + loserId;
    }
}
