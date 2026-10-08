package com.didicampus.domain.credit.model;

public enum CreditEventType {

    SETTLE(2, "完成结算"),
    GRAB_TIMEOUT_REVERT(-5, "抢中后超时未确认"),
    CANCEL_AFTER_GRAB(-10, "被抢单后取消"),
    DELIVERY_LATE(-3, "送达超时"),
    DISPUTE_LOSE(-8, "争议败诉");

    public static final int WINDOW_DAYS = 30;
    public static final int BASE_SCORE = 60;
    public static final int MIN_SCORE = 0;
    public static final int MAX_SCORE = 100;

    private final int delta;
    private final String description;

    CreditEventType(int delta, String description) {
        this.delta = delta;
        this.description = description;
    }

    public int delta() { return delta; }
    public String description() { return description; }
}
