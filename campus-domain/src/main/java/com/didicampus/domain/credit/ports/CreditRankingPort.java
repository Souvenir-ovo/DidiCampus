package com.didicampus.domain.credit.ports;

import java.util.List;

public interface CreditRankingPort {

    void update(long campusId, long userId, int score);

    List<Entry> top(long campusId, int limit);

    record Entry(long userId, int score) {}
}
