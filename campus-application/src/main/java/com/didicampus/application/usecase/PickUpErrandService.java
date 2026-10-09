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
 * 跑腿取货用例：ACCEPTED -> PICKED_UP。
 *
 * <p>这一步不涉及资金，但它是履约链路的必要状态节点。
 * 应用层负责 CAS、日志、通知与缓存失效，状态合法性仍由 Errand 聚合判断。</p>
 */
@Service
public class PickUpErrandService {

    private final ErrandRepository errandRepository;
    private final CacheEvictSupport cacheEvictSupport;
    private final RealtimeNotifier notifier;

    public PickUpErrandService(ErrandRepository errandRepository,
                               CacheEvictSupport cacheEvictSupport,
                               RealtimeNotifier notifier) {
        this.errandRepository = errandRepository;
        this.cacheEvictSupport = cacheEvictSupport;
        this.notifier = notifier;
    }

    public record Command(long errandId, long runnerId) {
    }

    @Transactional(rollbackFor = Exception.class)
    public void pickUp(Command command) {
        Errand errand = errandRepository.findById(command.errandId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ERRAND_NOT_FOUND,
                        "errandId=" + command.errandId()));

        long versionBefore = errand.version();
        errand.pickUp(command.runnerId(), versionBefore);

        if (errandRepository.casPickUp(
                command.errandId(),
                command.runnerId(),
                versionBefore) == 0) {
            throw new BusinessException(
                    ErrorCode.STALE_VERSION,
                    "pick up failed, errandId=" + command.errandId());
        }

        errandRepository.appendStatusLog(
                command.errandId(),
                ErrandStatus.ACCEPTED,
                ErrandStatus.PICKED_UP,
                errand.round(),
                command.runnerId());
        notifier.errandStatusChanged(
                errand.id(),
                errand.publisherId(),
                errand.grabberId(),
                ErrandStatus.PICKED_UP.name(),
                errand.round());
        cacheEvictSupport.evictAfterCommit(command.errandId());
    }
}
