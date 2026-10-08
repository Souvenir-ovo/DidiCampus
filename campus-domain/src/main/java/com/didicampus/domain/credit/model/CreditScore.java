package com.didicampus.domain.credit.model;

public final class CreditScore {

    private final long userId;
    private int score;
    private long version;

    public CreditScore(long userId, int score, long version) {
        this.userId = userId;
        this.score = Math.max(CreditEventType.MIN_SCORE,
                Math.min(CreditEventType.MAX_SCORE, score));
        this.version = version;
    }

    public static CreditScore initial(long userId) {
        return new CreditScore(userId, CreditEventType.BASE_SCORE, 0);
    }

    public void apply(int delta) {
        score = Math.max(
                CreditEventType.MIN_SCORE,
                Math.min(CreditEventType.MAX_SCORE, score + delta)
        );
        version++;
    }

    public long userId() { return userId; }
    public int score() { return score; }
    public long version() { return version; }
}
