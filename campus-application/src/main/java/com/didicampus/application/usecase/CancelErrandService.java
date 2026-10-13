package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.notify.ports.RealtimeNotifier;
import com.didicampus.domain.wallet.model.EscrowOrder;
import com.didicampus.domain.wallet.model.LedgerEntry;
import com.didicampus.domain.wallet.ports.FundAuditPort;
import com.didicampus.domain.wallet.ports.FundEventPort;
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * 发单人取消任务并退回托管资金。
 *
 * <p>只有 DRAFT/PUBLISHED 任务允许主动取消。退款不收取佣金，
 * 资金从平台托管账户全额回到发单人账户。</p>
 */
@Service
public class CancelErrandService {

    private final ErrandRepository errandRepository;
    private final FundEventPort fundEventPort;
    private final FundAuditPort auditPort;
    private final CancelErrandCommitService commitService;
    private final CacheEvictSupport cacheEvictSupport;
    private final RealtimeNotifier notifier;

    public CancelErrandService(ErrandRepository errandRepository,
                               FundEventPort fundEventPort,
                               FundAuditPort auditPort,
                               CancelErrandCommitService commitService,
                               CacheEvictSupport cacheEvictSupport,
                               RealtimeNotifier notifier) {
        this.errandRepository = errandRepository;
        this.fundEventPort = fundEventPort;
        this.auditPort = auditPort;
        this.commitService = commitService;
        this.cacheEvictSupport = cacheEvictSupport;
        this.notifier = notifier;
    }

    public enum Result {
        REFUNDED,
        ALREADY_REFUNDED,
        CONFLICT
    }

    public Result cancel(long errandId, long publisherId) {
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ERRAND_NOT_FOUND,
                        "errandId=" + errandId));

        if (errand.status() == ErrandStatus.CANCELLED) {
            return Result.ALREADY_REFUNDED;
        }
        if (errand.publisherId() != publisherId) {
            throw new BusinessException(
                    ErrorCode.NOT_PUBLISHER,
                    "actor=" + publisherId);
        }

        ErrandStatus sourceStatus = errand.status();
        long expectedVersion = errand.version();
        errand.cancelByPublisher(publisherId, expectedVersion);

        String bizNo = LedgerEntry.refundBizNo(errandId);
        FundEventPort.FundEvent event = new FundEventPort.FundEvent(
                bizNo,
                "REFUNDED",
                errandId,
                errand.publisherId(),
                errand.grabberId() == null ? 0L : errand.grabberId(),
                0L,
                0L);

        boolean committed;
        try {
            committed = fundEventPort.publishInTransaction(event, () ->
                    commitService.commit(new CancelErrandCommitService.Command(
                            errand.campusId(),
                            errandId,
                            errand.publisherId(),
                            expectedVersion,
                            sourceStatus,
                            errand.round(),
                            publisherId,
                            bizNo)));
        } catch (BusinessException ex) {
            if (ex.code() == ErrorCode.REFUND_CONFLICT) {
                auditPort.record(
                        bizNo,
                        "REFUND",
                        errandId,
                        publisherId,
                        "{\"reason\":\"conflict\"}",
                        false,
                        ex.getMessage());
                return Result.CONFLICT;
            }
            throw ex;
        }

        if (!committed) {
            auditPort.record(
                    bizNo,
                    "REFUND",
                    errandId,
                    publisherId,
                    "{\"reason\":\"escrow-not-held\"}",
                    false,
                    "escrow is not held");
            return Result.CONFLICT;
        }

        cacheEvictSupport.evictAfterCommit(errandId);
        auditPort.record(
                bizNo,
                "REFUND",
                errandId,
                publisherId,
                "{\"amount\":\"escrow\"}",
                true,
                null);
        notifier.errandStatusChanged(
                errandId,
                errand.publisherId(),
                errand.grabberId(),
                ErrandStatus.CANCELLED.name(),
                errand.round());
        return Result.REFUNDED;
    }
}
