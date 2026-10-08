package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.notify.ports.RealtimeNotifier;
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 抢中者确认接单：LOCKED -> ACCEPTED。
 *
 * <p>确认成功会递增任务版本，因此已经投递出去的旧超时消息即使重复消费，
 * 也会因为状态或轮次不匹配而被忽略。</p>
 */
@Service
public class ConfirmErrandService {

    private final ErrandRepository errandRepository;
    private final CacheEvictSupport cacheEvictSupport;
    private final RealtimeNotifier notifier;

    public ConfirmErrandService(ErrandRepository errandRepository,
                                CacheEvictSupport cacheEvictSupport,
                                RealtimeNotifier notifier) {
        this.errandRepository = errandRepository;
        this.cacheEvictSupport = cacheEvictSupport;
        this.notifier = notifier;
    }

    public record Command(long errandId, long runnerId) {
    }

    public enum Outcome {
        CONFIRMED,
        ALREADY_CONFIRMED
    }

    @Transactional(rollbackFor = Exception.class)
    public Outcome confirm(Command command) {
        Errand errand = errandRepository.findById(command.errandId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ERRAND_NOT_FOUND,
                        "errandId=" + command.errandId()));

        if (errand.status() == ErrandStatus.ACCEPTED) {
            return Outcome.ALREADY_CONFIRMED;
        }

        long versionBefore = errand.version();
        errand.acceptByRunner(command.runnerId(), versionBefore);

        if (errandRepository.casAccept(
                command.errandId(),
                command.runnerId(),
                versionBefore) == 0) {
            throw new BusinessException(
                    ErrorCode.STALE_VERSION,
                    "confirm failed, errandId=" + command.errandId());
        }

        errandRepository.appendStatusLog(
                command.errandId(),
                ErrandStatus.LOCKED,
                ErrandStatus.ACCEPTED,
                errand.round(),
                command.runnerId());
        notifier.errandStatusChanged(
                errand.id(),
                errand.publisherId(),
                errand.grabberId(),
                ErrandStatus.ACCEPTED.name(),
                errand.round());
        cacheEvictSupport.evictAfterCommit(command.errandId());
        return Outcome.CONFIRMED;
    }
}
