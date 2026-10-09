package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.errand.ports.LocalMessageRepository;
import com.didicampus.domain.notify.ports.RealtimeNotifier;
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 跑腿送达用例：PICKED_UP -> DELIVERED。
 *
 * <p>送达后需要在本地消息表登记一条自动结算延迟任务。
 * 状态变更和消息登记同事务提交，保证“送达成功后一定有结算保护”。</p>
 */
@Service
public class DeliverErrandService {

    private final ErrandRepository errandRepository;
    private final LocalMessageRepository localMessageRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final CacheEvictSupport cacheEvictSupport;
    private final RealtimeNotifier notifier;
    private final long autoSettleSeconds;

    public DeliverErrandService(ErrandRepository errandRepository,
                                LocalMessageRepository localMessageRepository,
                                SnowflakeIdGenerator idGenerator,
                                CacheEvictSupport cacheEvictSupport,
                                RealtimeNotifier notifier,
                                @Value("${didicampus.settle.auto-settle-seconds:86400}")
                                long autoSettleSeconds) {
        this.errandRepository = errandRepository;
        this.localMessageRepository = localMessageRepository;
        this.idGenerator = idGenerator;
        this.cacheEvictSupport = cacheEvictSupport;
        this.notifier = notifier;
        this.autoSettleSeconds = autoSettleSeconds;
    }

    public record Command(long errandId, long runnerId) {
    }

    @Transactional(rollbackFor = Exception.class)
    public void deliver(Command command) {
        Errand errand = errandRepository.findById(command.errandId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ERRAND_NOT_FOUND,
                        "errandId=" + command.errandId()));

        long versionBefore = errand.version();
        errand.deliver(command.runnerId(), versionBefore, Instant.now());

        if (errandRepository.casDeliver(
                command.errandId(),
                command.runnerId(),
                versionBefore) == 0) {
            throw new BusinessException(
                    ErrorCode.STALE_VERSION,
                    "deliver failed, errandId=" + command.errandId());
        }

        errandRepository.appendStatusLog(
                command.errandId(),
                ErrandStatus.PICKED_UP,
                ErrandStatus.DELIVERED,
                errand.round(),
                command.runnerId());

        localMessageRepository.enqueue(
                idGenerator.nextId(),
                DelayTaskPolicy.autoSettleKey(command.errandId()),
                DelayTaskPolicy.AUTO_SETTLE_TOPIC,
                DelayTaskPolicy.autoSettlePayload(command.errandId()),
                Instant.now().plusSeconds(autoSettleSeconds));

        notifier.errandStatusChanged(
                errand.id(),
                errand.publisherId(),
                errand.grabberId(),
                ErrandStatus.DELIVERED.name(),
                errand.round());
        cacheEvictSupport.evictAfterCommit(command.errandId());
    }
}
