package com.didicampus.application.usecase;

import com.didicampus.domain.credit.model.CreditEvent;
import com.didicampus.domain.credit.model.CreditEventType;
import com.didicampus.domain.credit.ports.CreditRepository;
import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.ports.DelayMessagePort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.errand.ports.LocalMessageRepository;
import com.didicampus.domain.grab.ports.CandidateQueuePort;
import com.didicampus.domain.grab.ports.GrabSlotPort;
import com.didicampus.domain.notify.ports.RealtimeNotifier;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * 确认超时后的流转编排。
 *
 * <p>旧消息通过“当前状态 + 当前轮次”校验失效；数据库 CAS 防止多个消费者
 * 同时处理同一轮；本地消息表的唯一 messageKey 防止同一轮重复登记。</p>
 */
@Service
public class TimeoutTransferService {

    private static final Logger log = LoggerFactory.getLogger(TimeoutTransferService.class);
    private static final long SLOT_KEY_TTL_SECONDS = 7 * 24 * 60 * 60L;

    private final ErrandRepository errandRepository;
    private final CandidateQueuePort candidateQueue;
    private final GrabSlotPort grabSlotPort;
    private final DelayMessagePort delayMessagePort;
    private final LocalMessageRepository localMessageRepository;
    private final TimeoutTransferCommitService commitService;
    private final CacheEvictSupport cacheEvictSupport;
    private final CreditRepository creditRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final RealtimeNotifier notifier;
    private final long confirmationTimeoutSeconds;
    private final int maxTransferRounds;

    public TimeoutTransferService(ErrandRepository errandRepository,
                                  CandidateQueuePort candidateQueue,
                                  GrabSlotPort grabSlotPort,
                                  DelayMessagePort delayMessagePort,
                                  LocalMessageRepository localMessageRepository,
                                  TimeoutTransferCommitService commitService,
                                  CacheEvictSupport cacheEvictSupport,
                                  CreditRepository creditRepository,
                                  SnowflakeIdGenerator idGenerator,
                                  RealtimeNotifier notifier,
                                  @Value("${didicampus.timeout.confirm-seconds:300}")
                                  long confirmationTimeoutSeconds,
                                  @Value("${didicampus.timeout.max-transfer-rounds:5}")
                                  int maxTransferRounds) {
        this.errandRepository = errandRepository;
        this.candidateQueue = candidateQueue;
        this.grabSlotPort = grabSlotPort;
        this.delayMessagePort = delayMessagePort;
        this.localMessageRepository = localMessageRepository;
        this.commitService = commitService;
        this.cacheEvictSupport = cacheEvictSupport;
        this.creditRepository = creditRepository;
        this.idGenerator = idGenerator;
        this.notifier = notifier;
        this.confirmationTimeoutSeconds = confirmationTimeoutSeconds;
        this.maxTransferRounds = maxTransferRounds;
    }

    public enum Outcome {
        TRANSFERRED,
        REOPENED,
        SKIPPED
    }

    public Outcome handleTimeout(long errandId, int expectedRound) {
        Errand current = errandRepository.findById(errandId).orElse(null);
        if (current == null
                || current.status() != ErrandStatus.LOCKED
                || current.round() != expectedRound) {
            return Outcome.SKIPPED;
        }

        if (current.round() >= maxTransferRounds) {
            return reopenAndRestoreSlot(current);
        }

        Optional<Long> candidate = candidateQueue.pollBest(errandId);
        if (candidate.isEmpty()) {
            return reopenAndRestoreSlot(current);
        }

        long nextRunnerId = candidate.get();
        TimeoutTransferCommitService.StepResult result =
                commitService.transfer(current, nextRunnerId, confirmationTimeoutSeconds);
        if (!result.applied()) {
            candidateQueue.offer(
                    errandId,
                    nextRunnerId,
                    System.currentTimeMillis());
            return Outcome.SKIPPED;
        }

        dispatch(result.pendingMessage());
        notifier.errandStatusChanged(
                current.id(),
                current.publisherId(),
                nextRunnerId,
                ErrandStatus.LOCKED.name(),
                expectedRound + 1);
        cacheEvictSupport.evictAfterCommit(errandId);
        return Outcome.TRANSFERRED;
    }

    /**
     * 抢单成功后登记第一轮确认超时消息。
     * 调用方应在抢单数据库事务返回后调用本方法。
     */
    public void scheduleFirstTimeout(long errandId, int round, long version) {
        dispatch(commitService.registerTimeout(
                errandId,
                round,
                version,
                confirmationTimeoutSeconds));
    }

    public long confirmationTimeoutSeconds() {
        return confirmationTimeoutSeconds;
    }

    private Outcome reopenAndRestoreSlot(Errand errand) {
        TimeoutTransferCommitService.StepResult result = commitService.reopen(errand);
        if (!result.applied()) {
            return Outcome.SKIPPED;
        }

        grabSlotPort.initSlot(
                errand.id(),
                errand.slotTotal(),
                SLOT_KEY_TTL_SECONDS);

        if (errand.grabberId() != null) {
            creditRepository.applyEvent(new CreditEvent(
                    idGenerator.nextId(),
                    CreditEvent.revertBizNo(errand.id(), errand.round()),
                    errand.grabberId(),
                    CreditEventType.GRAB_TIMEOUT_REVERT,
                    CreditEventType.GRAB_TIMEOUT_REVERT.delta(),
                    "ERRAND",
                    errand.id(),
                    Instant.now()));
        }

        notifier.errandStatusChanged(
                errand.id(),
                errand.publisherId(),
                null,
                ErrandStatus.PUBLISHED.name(),
                errand.round() + 1);
        cacheEvictSupport.evictAfterCommit(errand.id());
        return Outcome.REOPENED;
    }

    private void dispatch(TimeoutTransferCommitService.PendingMessage pending) {
        if (pending == null) {
            return;
        }
        try {
            delayMessagePort.send(
                    pending.topic(),
                    pending.messageKey(),
                    pending.payload(),
                    pending.deliverAt());
            localMessageRepository.markSent(pending.messageKey());
        } catch (RuntimeException ex) {
            // 本地消息表仍然是 PENDING，worker 后续可以补发。
            log.warn("delayed message send failed, messageKey={}", pending.messageKey(), ex);
        }
    }
}
