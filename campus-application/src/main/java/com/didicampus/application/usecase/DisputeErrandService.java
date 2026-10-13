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
 * 发起任务争议。
 *
 * <p>发单人和当前跑腿员都可以在履约阶段发起争议。争议只冻结订单状态，
 * 不直接改变托管资金，资金需要等后续仲裁结果决定去向。</p>
 */
@Service
public class DisputeErrandService {

    private final ErrandRepository errandRepository;
    private final CacheEvictSupport cacheEvictSupport;
    private final RealtimeNotifier notifier;

    public DisputeErrandService(ErrandRepository errandRepository,
                                CacheEvictSupport cacheEvictSupport,
                                RealtimeNotifier notifier) {
        this.errandRepository = errandRepository;
        this.cacheEvictSupport = cacheEvictSupport;
        this.notifier = notifier;
    }

    @Transactional(rollbackFor = Exception.class)
    public void raise(long errandId, long operatorId) {
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ERRAND_NOT_FOUND,
                        "errandId=" + errandId));

        ErrandStatus sourceStatus = errand.status();
        long expectedVersion = errand.version();
        errand.raiseDispute(operatorId, expectedVersion);

        if (errandRepository.casDispute(errandId, expectedVersion) == 0) {
            throw new BusinessException(
                    ErrorCode.STALE_VERSION,
                    "dispute failed, errandId=" + errandId);
        }

        errandRepository.appendStatusLog(
                errandId,
                sourceStatus,
                ErrandStatus.DISPUTED,
                errand.round(),
                operatorId);
        notifier.errandStatusChanged(
                errand.id(),
                errand.publisherId(),
                errand.grabberId(),
                ErrandStatus.DISPUTED.name(),
                errand.round());
        cacheEvictSupport.evictAfterCommit(errandId);
    }
}
