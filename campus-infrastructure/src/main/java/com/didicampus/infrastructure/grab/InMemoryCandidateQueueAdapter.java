package com.didicampus.infrastructure.grab;

import com.didicampus.domain.grab.ports.CandidateQueuePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "didicampus.grab.queue-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryCandidateQueueAdapter implements CandidateQueuePort {

    private final ConcurrentMap<Long, QueueState> queues = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public void offer(long errandId, long runnerId, double score) {
        QueueState state = queues.computeIfAbsent(errandId, id -> new QueueState());
        synchronized (state) {
            Candidate candidate = new Candidate(runnerId, score, sequence.incrementAndGet());
            state.latest.put(runnerId, candidate);
            state.heap.offer(candidate);
        }
    }

    @Override
    public Optional<Long> pollBest(long errandId) {
        QueueState state = queues.get(errandId);
        if (state == null) {
            return Optional.empty();
        }
        synchronized (state) {
            while (!state.heap.isEmpty()) {
                Candidate candidate = state.heap.poll();
                if (state.latest.remove(candidate.runnerId(), candidate)) {
                    return Optional.of(candidate.runnerId());
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public long size(long errandId) {
        QueueState state = queues.get(errandId);
        if (state == null) {
            return 0L;
        }
        synchronized (state) {
            return state.latest.size();
        }
    }

    private static final class QueueState {
        private final Map<Long, Candidate> latest = new HashMap<>();
        private final PriorityQueue<Candidate> heap = new PriorityQueue<>(
                Comparator.comparingDouble(Candidate::score)
                        .thenComparingLong(Candidate::sequence));
    }

    private record Candidate(long runnerId, double score, long sequence) {
    }
}
