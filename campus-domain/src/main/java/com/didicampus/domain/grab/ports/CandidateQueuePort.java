package com.didicampus.domain.grab.ports;

import java.util.Optional;

public interface CandidateQueuePort {

    void offer(long errandId, long runnerId, double score);

    Optional<Long> pollBest(long errandId);

    long size(long errandId);
}
