package com.didicampus.application.usecase;

import com.didicampus.domain.credit.model.CreditEvent;
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
import com.didicampus.shared.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 仲裁应用服务：决定争议资金最终归属。
 *
 * <p>仲裁员只选择结果方向，真正的状态和资金更新分别委托给
 * 独立事务 Bean，保证 Spring 事务代理不会被同类调用绕过。</p>
 */
@Service
public class ArbitrateErrandService {

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final FundEventPort fundEventPort;
    private final FundAuditPort auditPort;
    private final ArbitrateRefundCommitService refundCommitService;
    private final ArbitrateSettleCommitService settleCommitService;
    private final CacheEvictSupport cacheEvictSupport;
    private final CreditRepository creditRepository;
    private final CreditRankingPort creditRankingPort;
    private final SnowflakeIdGenerator idGenerator;
    private final RealtimeNotifier notifier;
    private final double commissionRate;

    public ArbitrateErrandService(ErrandRepository errandRepository,
                                  WalletRepository walletRepository,
                                  FundEventPort fundEventPort,
                                  FundAuditPort auditPort,
                                  ArbitrateRefundCommitService refundCommitService,
                                  ArbitrateSettleCommitService settleCommitService,
                                  CacheEvictSupport cacheEvictSupport,
                                  CreditRepository creditRepository,
                                  CreditRankingPort creditRankingPort,
                                  SnowflakeIdGenerator idGenerator,
                                  RealtimeNotifier notifier,
                                  @Value("${didicampus.settle.commission-rate:0.05}")
                                  double commissionRate) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.fundEventPort = fundEventPort;
        this.auditPort = auditPort;
        this.refundCommitService = refundCommitService;
        this.settleCommitService = settleCommitService;
        this.cacheEvictSupport = cacheEvictSupport;
        this.creditRepository = creditRepository;
        this.creditRankingPort = creditRankingPort;
        this.idGenerator = idGenerator;
        this.notifier = notifier;
        this.commissionRate = commissionRate;
    }

    public enum Favor {
        RUNNER,
        PUBLISHER
    }

    public enum Result {
        SETTLED_TO_RUNNER,
        REFUNDED_TO_PUBLISHER,
        ALREADY_DONE,
        CONFLICT
    }

    public Result arbitrate(long errandId, Favor favor, long operatorId) {
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ERRAND_NOT_FOUND,
                        "errandId=" + errandId));

        if (errand.status() == ErrandStatus.SETTLED
                || errand.status() == ErrandStatus.REFUNDED) {
            return Result.ALREADY_DONE;
        }
        if (errand.status() != ErrandStatus.DISPUTED) {
            throw new BusinessException(
                    ErrorCode.NOT_ARBITRABLE,
                    "status=" + errand.status());
        }

        if (favor == Favor.PUBLISHER) {
            return refundToPublisher(errand, operatorId);
        }
        return settleToRunner(errand, operatorId);
    }

    private Result refundToPublisher(Errand errand, long operatorId) {
        EscrowOrder escrow = escrowOf(errand);
        if (escrow.status() != EscrowOrder.EscrowStatus.HELD) {
            return Result.ALREADY_DONE;
        }

        String bizNo = LedgerEntry.refundBizNo(errand.id());
        FundEventPort.FundEvent event = new FundEventPort.FundEvent(
                bizNo,
                "REFUNDED",
                errand.id(),
                errand.publisherId(),
                runnerOrZero(errand),
                escrow.amount().cents(),
                0L);

        boolean committed;
        try {
            committed = fundEventPort.publishInTransaction(event, () ->
                    refundCommitService.commit(new ArbitrateRefundCommitService.Command(
                            errand.campusId(),
                            errand.id(),
                            errand.publisherId(),
                            errand.version(),
                            errand.round(),
                            operatorId,
                            bizNo)));
        } catch (BusinessException ex) {
            if (ex.code() == ErrorCode.REFUND_CONFLICT) {
                auditPort.record(
                        bizNo,
                        "ARBITRATE",
                        errand.id(),
                        operatorId,
                        "{\"favor\":\"PUBLISHER\",\"reason\":\"conflict\"}",
                        false,
                        ex.getMessage());
                return Result.CONFLICT;
            }
            throw ex;
        }

        if (!committed) {
            recordConflict(bizNo, errand.id(), operatorId, "refund-not-held");
            return Result.CONFLICT;
        }

        applyDisputeLoss(errand);
        cacheEvictSupport.evictAfterCommit(errand.id());
        auditPort.record(
                bizNo,
                "ARBITRATE",
                errand.id(),
                operatorId,
                "{\"favor\":\"PUBLISHER\",\"amount\":%d}".formatted(escrow.amount().cents()),
                true,
                null);
        notifier.errandStatusChanged(
                errand.id(),
                errand.publisherId(),
                errand.grabberId(),
                ErrandStatus.REFUNDED.name(),
                errand.round());
        return Result.REFUNDED_TO_PUBLISHER;
    }

    private Result settleToRunner(Errand errand, long operatorId) {
        if (errand.grabberId() == null) {
            throw new BusinessException(
                    ErrorCode.NOT_CURRENT_GRABBER,
                    "disputed errand has no runner");
        }

        EscrowOrder escrow = escrowOf(errand);
        if (escrow.status() != EscrowOrder.EscrowStatus.HELD) {
            return Result.ALREADY_DONE;
        }

        Money commission = Money.fromCents(
                (long) Math.floor(escrow.amount().cents() * commissionRate));
        Money runnerAmount = escrow.amount().minus(commission);
        String bizNo = LedgerEntry.settleBizNo(errand.id());
        FundEventPort.FundEvent event = new FundEventPort.FundEvent(
                bizNo,
                "ARBITRATED",
                errand.id(),
                errand.publisherId(),
                errand.grabberId(),
                runnerAmount.cents(),
                commission.cents());

        boolean committed;
        try {
            committed = fundEventPort.publishInTransaction(event, () ->
                    settleCommitService.commit(new ArbitrateSettleCommitService.Command(
                            errand.campusId(),
                            errand.id(),
                            errand.publisherId(),
                            errand.grabberId(),
                            errand.version(),
                            errand.round(),
                            operatorId,
                            escrow.amount(),
                            runnerAmount,
                            commission,
                            bizNo)));
        } catch (BusinessException ex) {
            if (ex.code() == ErrorCode.SETTLE_CONFLICT) {
                auditPort.record(
                        bizNo,
                        "ARBITRATE",
                        errand.id(),
                        operatorId,
                        "{\"favor\":\"RUNNER\",\"reason\":\"conflict\"}",
                        false,
                        ex.getMessage());
                return Result.CONFLICT;
            }
            throw ex;
        }

        if (!committed) {
            recordConflict(bizNo, errand.id(), operatorId, "settle-not-held");
            return Result.CONFLICT;
        }

        cacheEvictSupport.evictAfterCommit(errand.id());
        creditRankingPort.update(
                errand.campusId(),
                errand.grabberId(),
                creditRepository.scoreOf(errand.grabberId()));
        auditPort.record(
                bizNo,
                "ARBITRATE",
                errand.id(),
                operatorId,
                "{\"favor\":\"RUNNER\",\"runner\":%d,\"commission\":%d}"
                        .formatted(runnerAmount.cents(), commission.cents()),
                true,
                null);
        notifier.errandStatusChanged(
                errand.id(),
                errand.publisherId(),
                errand.grabberId(),
                ErrandStatus.SETTLED.name(),
                errand.round());
        notifier.creditChanged(
                errand.grabberId(),
                creditRepository.scoreOf(errand.grabberId()),
                CreditEventType.SETTLE.delta(),
                CreditEventType.SETTLE.description());
        return Result.SETTLED_TO_RUNNER;
    }

    private void applyDisputeLoss(Errand errand) {
        if (errand.grabberId() == null) {
            return;
        }
        creditRepository.applyEvent(new CreditEvent(
                idGenerator.nextId(),
                CreditEvent.disputeLoseBizNo(errand.id(), errand.grabberId()),
                errand.grabberId(),
                CreditEventType.DISPUTE_LOSE,
                CreditEventType.DISPUTE_LOSE.delta(),
                "ERRAND",
                errand.id(),
                java.time.Instant.now()));
        creditRankingPort.update(
                errand.campusId(),
                errand.grabberId(),
                creditRepository.scoreOf(errand.grabberId()));
        notifier.creditChanged(
                errand.grabberId(),
                creditRepository.scoreOf(errand.grabberId()),
                CreditEventType.DISPUTE_LOSE.delta(),
                "争议败诉");
    }

    private EscrowOrder escrowOf(Errand errand) {
        return walletRepository.findEscrowByErrandId(errand.campusId(), errand.id())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ESCROW_NOT_FOUND,
                        "errandId=" + errand.id()));
    }

    private long runnerOrZero(Errand errand) {
        return errand.grabberId() == null ? 0L : errand.grabberId();
    }

    private void recordConflict(String bizNo, long errandId, long operatorId, String reason) {
        auditPort.record(
                bizNo,
                "ARBITRATE",
                errandId,
                operatorId,
                "{\"reason\":\"%s\"}".formatted(reason),
                false,
                reason);
    }
}
