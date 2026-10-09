package com.didicampus.infrastructure.cache;

import com.didicampus.domain.credit.ports.CreditRankingPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(name = "didicampus.credit.ranking-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryCreditRankingAdapter implements CreditRankingPort {

    private final ConcurrentMap<Long, ConcurrentMap<Long, Integer>> rankings = new ConcurrentHashMap<>();

    @Override
    public void update(long campusId, long userId, int score) {
        rankings.computeIfAbsent(campusId, id -> new ConcurrentHashMap<>())
                .put(userId, score);
    }

    @Override
    public List<Entry> top(long campusId, int limit) {
        return rankings.getOrDefault(campusId, new ConcurrentHashMap<>())
                .entrySet()
                .stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(Math.max(0, limit))
                .map(entry -> new Entry(entry.getKey(), entry.getValue()))
                .toList();
    }
}
