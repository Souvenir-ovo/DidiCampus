package com.didicampus.application.usecase;

import com.didicampus.domain.credit.ports.CreditRepository;
import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.ports.ErrandQueryPort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.grab.model.SlotOutcome;
import com.didicampus.domain.grab.ports.CandidateQueuePort;
import com.didicampus.domain.grab.ports.GrabRateLimiterPort;
import com.didicampus.domain.grab.ports.GrabSlotPort;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 抢单应用服务。
 *
 * <p>调用顺序对应四道防线：热点限流、资格校验、名额原子裁决、数据库 CAS。
 * Redis 已成功但数据库提交失败时，必须执行 rollback，避免名额泄漏。</p>
 */
@Service
public class GrabErrandService {

    private static final Logger log = LoggerFactory.getLogger(GrabErrandService.class);

    private final GrabSlotPort grabSlotPort;
    private final CandidateQueuePort candidateQueue;
    private final ErrandRepository errandRepository;
    private final GrabErrandCommitService commitService;
    private final CreditRepository creditRepository;
    private final ErrandQueryPort errandQueryPort;
    private final GrabRateLimiterPort rateLimiter;
    private final SnowflakeIdGenerator idGenerator;
    private final TimeoutTransferService timeoutTransferService;
    private final int minimumCreditScore;
    private final int maximumOngoing;

    public GrabErrandService(GrabSlotPort grabSlotPort,
                             CandidateQueuePort candidateQueue,
                             ErrandRepository errandRepository,
                             GrabErrandCommitService commitService,
                             CreditRepository creditRepository,
                             ErrandQueryPort errandQueryPort,
                             GrabRateLimiterPort rateLimiter,
                             SnowflakeIdGenerator idGenerator,
                             TimeoutTransferService timeoutTransferService,
                             @Value("${didicampus.credit.min-score:40}") int minimumCreditScore,
                             @Value("${didicampus.credit.max-ongoing:5}") int maximumOngoing) {
        this.grabSlotPort = grabSlotPort;
        this.candidateQueue = candidateQueue;
        this.errandRepository = errandRepository;
        this.commitService = commitService;
        this.creditRepository = creditRepository;
        this.errandQueryPort = errandQueryPort;
        this.rateLimiter = rateLimiter;
        this.idGenerator = idGenerator;
        this.timeoutTransferService = timeoutTransferService;
        this.minimumCreditScore = minimumCreditScore;
        this.maximumOngoing = maximumOngoing;
    }

    public record Command(long errandId, long runnerId, String requestId) {
    }

    public record Result(ErrorCode code, boolean grabbed, Long candidateRank) {
        public static Result success() {
            return new Result(ErrorCode.OK, true, null);
        }

        public static Result rejected(ErrorCode code, Long candidateRank) {
            return new Result(code, false, candidateRank);
        }
    }

    public Result grab(Command command) {
        if (!rateLimiter.tryPass(command.errandId(), command.runnerId())) {
            return Result.rejected(ErrorCode.GRAB_RATE_LIMITED, null);
        }

        if (creditRepository.scoreOf(command.runnerId()) < minimumCreditScore) {
            return Result.rejected(ErrorCode.CREDIT_TOO_LOW, null);
        }
        if (errandQueryPort.countOngoingByRunner(command.runnerId()) >= maximumOngoing) {
            return Result.rejected(ErrorCode.TOO_MANY_ONGOING, null);
        }

        SlotOutcome outcome = grabSlotPort.tryAcquire(
                command.errandId(),
                command.runnerId(),
                command.requestId());
        switch (outcome) {
            case DUPLICATE_REQUEST -> {
                return Result.success();
            }
            case ALREADY_GRABBED -> {
                return Result.rejected(ErrorCode.ALREADY_GRABBED, null);
            }
            case NOT_GRABBABLE -> {
                return Result.rejected(ErrorCode.ERRAND_NOT_GRABBABLE, null);
            }
            case SLOT_FULL -> {
                return Result.rejected(ErrorCode.SLOT_FULL, enqueueCandidate(command));
            }
            case ACQUIRED -> {
                // 继续进入数据库最终裁决。
            }
        }

        Errand snapshot;
        try {
            snapshot = errandRepository.findById(command.errandId()).orElse(null);
            if (snapshot == null) {
                rollback(command);
                return Result.rejected(ErrorCode.ERRAND_NOT_FOUND, null);
            }

            boolean committed = commitService.commit(
                    new GrabErrandCommitService.CommitCommand(
                            command.errandId(),
                            command.runnerId(),
                            idGenerator.nextId()));
            if (!committed) {
                rollback(command);
                return Result.rejected(
                        ErrorCode.GRAB_CONFLICT,
                        enqueueCandidate(command));
            }
        } catch (RuntimeException ex) {
            rollback(command);
            return Result.rejected(ErrorCode.GRAB_CONFLICT, null);
        }

        // 数据库已经提交后，消息登记失败不能再回滚 Redis 名额。
        // 否则会形成“数据库已锁定、Redis 却重新开放”的反向不一致。
        try {
            timeoutTransferService.scheduleFirstTimeout(
                    command.errandId(),
                    snapshot.round(),
                    snapshot.version() + 1);
        } catch (RuntimeException ex) {
            log.error("first timeout registration failed, errandId={}",
                    command.errandId(), ex);
        }
        return Result.success();
    }

    private Long enqueueCandidate(Command command) {
        double priority = System.currentTimeMillis()
                - Math.min(creditRepository.scoreOf(command.runnerId()), 100) * 10.0;
        candidateQueue.offer(command.errandId(), command.runnerId(), priority);
        return candidateQueue.size(command.errandId());
    }

    private void rollback(Command command) {
        grabSlotPort.rollback(
                command.errandId(),
                command.runnerId(),
                command.requestId());
    }
}
