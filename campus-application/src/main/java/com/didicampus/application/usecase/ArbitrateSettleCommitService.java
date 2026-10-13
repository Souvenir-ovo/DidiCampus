package com.didicampus.application.usecase;

import com.didicampus.domain.credit.model.CreditEvent;
import com.didicampus.domain.credit.model.CreditEventType;
import com.didicampus.domain.credit.ports.CreditRepository;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.wallet.model.AccountType;
import com.didicampus.domain.wallet.model.EscrowOrder;
import com.didicampus.domain.wallet.model.LedgerEntry;
import com.didicampus.domain.wallet.model.WalletAccount;
import com.didicampus.domain.wallet.ports.WalletRepository;
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.Money;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仲裁支持跑腿时的本地资金事务。
 *
 * <p>资金分配与普通结算一致，但订单入口是 DISPUTED，
 * 因此必须使用单独的 casSettleFromDispute。</p>
 */
@Service
public class ArbitrateSettleCommitService {

    private static final long ESCROW_OWNER_ID = -1L;
    private static final long COMMISSION_OWNER_ID = -2L;

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final CreditRepository creditRepository;
    private final SnowflakeIdGenerator idGenerator;

    public ArbitrateSettleCommitService(ErrandRepository errandRepository,
                                        WalletRepository walletRepository,
                                        CreditRepository creditRepository,
                                        SnowflakeIdGenerator idGenerator) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.creditRepository = creditRepository;
        this.idGenerator = idGenerator;
    }

    public record Command(long campusId,
                          long errandId,
                          long publisherId,
                          long runnerId,
                          long expectedVersion,
                          int round,
                          long operatorId,
                          Money escrowAmount,
                          Money runnerAmount,
                          Money commissionAmount,
                          String bizNo) {
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean commit(Command command) {
        int escrowChanged = walletRepository.casEscrowStatus(
                command.campusId(),
                command.errandId(),
                EscrowOrder.EscrowStatus.HELD,
                EscrowOrder.EscrowStatus.RELEASED);
        if (escrowChanged == 0) {
            return false;
        }

        if (errandRepository.casSettleFromDispute(
                command.errandId(), command.expectedVersion()) == 0) {
            throw new BusinessException(
                    ErrorCode.SETTLE_CONFLICT,
                    "arbitration settle cas failed, errandId=" + command.errandId());
        }

        errandRepository.appendStatusLog(
                command.errandId(),
                ErrandStatus.DISPUTED,
                ErrandStatus.SETTLED,
                command.round(),
                command.operatorId());

        WalletAccount escrowAccount = walletRepository
                .findByOwner(ESCROW_OWNER_ID, AccountType.ESCROW)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACCOUNT_NOT_FOUND,
                        "escrow account"));
        WalletAccount runnerAccount = walletRepository
                .findByOwner(command.runnerId(), AccountType.USER)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACCOUNT_NOT_FOUND,
                        "runner=" + command.runnerId()));

        if (walletRepository.casDebit(escrowAccount.id(), command.escrowAmount()) == 0) {
            throw new BusinessException(ErrorCode.SETTLE_CONFLICT, "escrow debit failed");
        }
        if (walletRepository.casCredit(runnerAccount.id(), command.runnerAmount()) == 0) {
            throw new BusinessException(ErrorCode.SETTLE_CONFLICT, "runner credit failed");
        }

        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(),
                command.bizNo(),
                escrowAccount.id(),
                command.publisherId(),
                LedgerEntry.Direction.DEBIT,
                command.escrowAmount(),
                escrowAccount.available().minus(command.escrowAmount()),
                LedgerEntry.RefType.SETTLE,
                command.errandId()));
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(),
                command.bizNo(),
                runnerAccount.id(),
                command.runnerId(),
                LedgerEntry.Direction.CREDIT,
                command.runnerAmount(),
                runnerAccount.available().plus(command.runnerAmount()),
                LedgerEntry.RefType.SETTLE,
                command.errandId()));

        if (!command.commissionAmount().isZero()) {
            WalletAccount commissionAccount = walletRepository
                    .findByOwner(COMMISSION_OWNER_ID, AccountType.COMMISSION)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.ACCOUNT_NOT_FOUND,
                            "commission account"));
            if (walletRepository.casCredit(
                    commissionAccount.id(), command.commissionAmount()) == 0) {
                throw new BusinessException(
                        ErrorCode.SETTLE_CONFLICT,
                        "commission credit failed");
            }
            walletRepository.insertLedger(new LedgerEntry(
                    idGenerator.nextId(),
                    command.bizNo(),
                    commissionAccount.id(),
                    COMMISSION_OWNER_ID,
                    LedgerEntry.Direction.CREDIT,
                    command.commissionAmount(),
                    commissionAccount.available().plus(command.commissionAmount()),
                    LedgerEntry.RefType.SETTLE,
                    command.errandId()));
        }

        creditRepository.applyEvent(new CreditEvent(
                idGenerator.nextId(),
                CreditEvent.settleBizNo(command.errandId()),
                command.runnerId(),
                CreditEventType.SETTLE,
                CreditEventType.SETTLE.delta(),
                "ERRAND",
                command.errandId(),
                java.time.Instant.now()));
        return true;
    }
}
