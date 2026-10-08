package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.errand.ports.LocalMessageRepository;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 超时流转的数据库事务步骤。
 *
 * <p>状态 CAS、状态日志和下一轮延迟消息登记必须在一个数据库事务中完成。
 * 真正发送消息属于外部 IO，由上层在事务提交后执行。</p>
 */
@Service
public class TimeoutTransferCommitService {

    private final ErrandRepository errandRepository;
    private final LocalMessageRepository localMessageRepository;
    private final SnowflakeIdGenerator idGenerator;

    public TimeoutTransferCommitService(ErrandRepository errandRepository,
                                       LocalMessageRepository localMessageRepository,
                                       SnowflakeIdGenerator idGenerator) {
        this.errandRepository = errandRepository;
        this.localMessageRepository = localMessageRepository;
        this.idGenerator = idGenerator;
    }

    public record PendingMessage(String topic,
                                 String messageKey,
                                 String payload,
                                 Instant deliverAt) {
    }

    public record StepResult(boolean applied, PendingMessage pendingMessage) {
        public static StepResult skipped() {
            return new StepResult(false, null);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public StepResult transfer(Errand errand,
                               long nextRunnerId,
                               long timeoutSeconds) {
        if (errandRepository.casTransferToNext(
                errand.id(),
                nextRunnerId,
                errand.version(),
                errand.round()) == 0) {
            return StepResult.skipped();
        }

        int nextRound = errand.round() + 1;
        errandRepository.appendStatusLog(
                errand.id(),
                ErrandStatus.LOCKED,
                ErrandStatus.LOCKED,
                nextRound,
                nextRunnerId);

        return new StepResult(
                true,
                registerTimeout(errand.id(), nextRound, errand.version() + 1, timeoutSeconds));
    }

    @Transactional(rollbackFor = Exception.class)
    public StepResult reopen(Errand errand) {
        if (errandRepository.casRevertToPublished(
                errand.id(),
                errand.version(),
                errand.round()) == 0) {
            return StepResult.skipped();
        }

        errandRepository.appendStatusLog(
                errand.id(),
                ErrandStatus.LOCKED,
                ErrandStatus.PUBLISHED,
                errand.round() + 1,
                Errand.SYSTEM_OPERATOR);
        return new StepResult(true, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public PendingMessage registerTimeout(long errandId,
                                          int round,
                                          long version,
                                          long timeoutSeconds) {
        String key = DelayTaskPolicy.timeoutKey(errandId, round);
        String payload = DelayTaskPolicy.timeoutPayload(errandId, round, version);
        Instant deliverAt = Instant.now().plusSeconds(timeoutSeconds);

        boolean inserted = localMessageRepository.enqueue(
                idGenerator.nextId(),
                key,
                DelayTaskPolicy.CONFIRM_TIMEOUT_TOPIC,
                payload,
                deliverAt);
        return inserted
                ? new PendingMessage(
                DelayTaskPolicy.CONFIRM_TIMEOUT_TOPIC,
                key,
                payload,
                deliverAt)
                : null;
    }
}
