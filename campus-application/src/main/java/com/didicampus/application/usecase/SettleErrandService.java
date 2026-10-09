package com.didicampus.application.usecase;

import com.didicampus.domain.credit.model.CreditEventType;
import com.didicampus.domain.credit.ports.CreditRankingPort;
import com.didicampus.domain.credit.ports.CreditRepository;
import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.notify.ports.RealtimeNotifier;
import com.didicampus.domain.wallet.model.EscrowOrder;
import com.didicampus.domain.wallet.model.LedgerEntry;
import com.didicampus.domain.wallet.ports.FundAuditPort;
import com.didicampus.domain.wallet.ports.FundEventPort;
import com.didicampus.domain.wallet.ports.WalletRepository;
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 结算应用服务：DELIVERED -> SETTLED，并将托管资金释放给跑腿和佣金账户。
 */
@Service
public class SettleErrandService {

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final FundEventPort fundEventPort;
    private final FundAuditPort auditPort;
    private final SettleErrandCommitService commitService;
    private final CacheEvictSupport cacheEvictSupport;
    private final CreditRepository creditRepository;
    private final CreditRankingPort creditRankingPort;
    private final RealtimeNotifier notifier;
    private final double commissionRate;

    public SettleErrandService(ErrandRepository errandRepository,
                               WalletRepository walletRepository,
                               FundEventPort fundEventPort,
                               FundAuditPort auditPort,
                               SettleErrandCommitService commitService,
                               CacheEvictSupport cacheEvictSupport,
                               CreditRepository creditRepository,
                               CreditRankingPort creditRankingPort,
                               RealtimeNotifier notifier,
                               @Value("${didicampus.settle.commission-rate:0.05}")
                               double commissionRate) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.fundEventPort = fundEventPort;
        this.auditPort = auditPort;
        this.commitService = commitService;
        this.cacheEvictSupport = cacheEvictSupport;
        this.creditRepository = creditRepository;
        this.creditRankingPort = creditRankingPort;
        this.notifier = notifier;
        this.commissionRate = commissionRate;
    }

    public enum Result {
        SETTLED,
        ALREADY_SETTLED,
        CONFLICT
    }

    public Result settle(long errandId, long operatorId) {
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ERRAND_NOT_FOUND,
                        "errandId=" + errandId));

        if (errand.status() == ErrandStatus.SETTLED) {
            return Result.ALREADY_SETTLED;
        }
        long versionBefore = errand.version();
        errand.settle(operatorId, versionBefore);

        if (errand.grabberId() == null) {
            throw new BusinessException(ErrorCode.NOT_CURRENT_GRABBER, "settle without runner");
        }

        EscrowOrder escrow = walletRepository
                .findEscrowByErrandId(errand.campusId(), errandId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ESCROW_NOT_FOUND,
                        "errandId=" + errandId));

        Money commissionAmount = commissionOf(escrow.amount());
        Money runnerAmount = escrow.amount().minus(commissionAmount);
        String bizNo = LedgerEntry.settleBizNo(errandId);

        FundEventPort.FundEvent event = new FundEventPort.FundEvent(
                bizNo,
                "SETTLED",
                errandId,
                errand.publisherId(),
                errand.grabberId(),
                runnerAmount.cents(),
                commissionAmount.cents());

        boolean committed;
        try {
            committed = fundEventPort.publishInTransaction(event, () -> commitService.commit(
                    new SettleErrandCommitService.Command(
                            errand.campusId(),
                            errandId,
                            errand.publisherId(),
                            errand.grabberId(),
                            versionBefore,
                            errand.round(),
                            operatorId,
                            escrow.amount(),
                            runnerAmount,
                            commissionAmount,
                            bizNo)));
        } catch (BusinessException ex) {
            if (ex.code() == ErrorCode.SETTLE_CONFLICT) {
                auditPort.record(bizNo, "SETTLE", errandId, operatorId,
                        "{\"reason\":\"conflict\"}", false, ex.getMessage());
                return Result.CONFLICT;
            }
            throw ex;
        }

        if (!committed) {
            auditPort.record(bizNo, "SETTLE", errandId, operatorId,
                    "{\"reason\":\"not-held\"}", false, "escrow is not held");
            return Result.CONFLICT;
        }

        cacheEvictSupport.evictAfterCommit(errandId);
        creditRankingPort.update(
                errand.campusId(),
                errand.grabberId(),
                creditRepository.scoreOf(errand.grabberId()));
        auditPort.record(
                bizNo,
                "SETTLE",
                errandId,
                operatorId,
                "{\"runner\":%d,\"commission\":%d}".formatted(
                        runnerAmount.cents(), commissionAmount.cents()),
                true,
                null);
        notifier.errandStatusChanged(
                errandId,
                errand.publisherId(),
                errand.grabberId(),
                ErrandStatus.SETTLED.name(),
                errand.round());
        notifier.creditChanged(
                errand.grabberId(),
                creditRepository.scoreOf(errand.grabberId()),
                CreditEventType.SETTLE.delta(),
                CreditEventType.SETTLE.description());
        return Result.SETTLED;
    }

    private Money commissionOf(Money escrowAmount) {
        long commissionCents = (long) Math.floor(escrowAmount.cents() * commissionRate);
        return Money.fromCents(commissionCents);
    }
}
