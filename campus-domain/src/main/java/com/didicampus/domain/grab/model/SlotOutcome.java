package com.didicampus.domain.grab.model;

public enum SlotOutcome {

    ACQUIRED(1),
    SLOT_FULL(0),
    ALREADY_GRABBED(-1),
    DUPLICATE_REQUEST(-2),
    NOT_GRABBABLE(-3);

    private final long code;

    SlotOutcome(long code) {
        this.code = code;
    }

    public long code() {
        return code;
    }

    public static SlotOutcome fromCode(long rawCode) {
        for (SlotOutcome outcome : values()) {
            if (outcome.code == rawCode) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("未知的抢单结果码: " + rawCode);
    }
}
