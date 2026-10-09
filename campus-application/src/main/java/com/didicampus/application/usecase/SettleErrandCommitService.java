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
 * 结算的本地数据库事务步骤。
 *
 * <p>托管状态、任务状态、账户余额、资金流水和信用事件必须一起提交。
 * 任何中途冲突都抛出运行时业务异常，让 Spring 回滚已经执行过的 CAS。</p>
 */
@Service
public class SettleErrandCommitService {

    private static final long ESCROW_OWNER_ID = -1L;
    private static final long COMMISSION_OWNER_ID = -2L;

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final CreditRepository creditRepository;
    private final SnowflakeIdGenerator idGenerator;

    public SettleErrandCommitService(ErrandRepository errandRepository,
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
        int escrowUpdated = walletRepository.casEscrowStatus(
                command.campusId(),
                command.errandId(),
                EscrowOrder.EscrowStatus.HELD,
                EscrowOrder.EscrowStatus.RELEASED);
        if (escrowUpdated == 0) {
            return false;
        }

        if (errandRepository.casSettle(command.errandId(), command.expectedVersion()) == 0) {
            throw new BusinessException(
                    ErrorCode.SETTLE_CONFLICT,
                    "errand cas failed, errandId=" + command.errandId());
        }
        errandRepository.appendStatusLog(
                command.errandId(),
                ErrandStatus.DELIVERED,
                ErrandStatus.SETTLED,
                command.round(),
                command.operatorId());

        WalletAccount escrowAccount = walletRepository
                .findByOwner(ESCROW_OWNER_ID, AccountType.ESCROW)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "escrow account"));
        WalletAccount runnerAccount = walletRepository
                .findByOwner(command.runnerId(), AccountType.USER)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "runner=" + command.runnerId()));

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
                    .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "commission account"));
            if (walletRepository.casCredit(commissionAccount.id(), command.commissionAmount()) == 0) {
                throw new BusinessException(ErrorCode.SETTLE_CONFLICT, "commission credit failed");
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
