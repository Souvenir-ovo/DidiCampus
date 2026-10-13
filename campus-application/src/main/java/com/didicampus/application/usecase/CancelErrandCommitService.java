package com.didicampus.application.usecase;

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
 * 取消退款的本地事务步骤。
 *
 * <p>订单状态、托管状态、钱包余额和资金流水必须作为一个数据库事务提交。
 * 任意 CAS 失败都会抛出异常，交给 Spring 回滚前面已经执行的数据库更新。</p>
 */
@Service
public class CancelErrandCommitService {

    private static final long ESCROW_OWNER_ID = -1L;

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final SnowflakeIdGenerator idGenerator;

    public CancelErrandCommitService(ErrandRepository errandRepository,
                                     WalletRepository walletRepository,
                                     SnowflakeIdGenerator idGenerator) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.idGenerator = idGenerator;
    }

    public record Command(long campusId,
                          long errandId,
                          long publisherId,
                          long expectedVersion,
                          ErrandStatus sourceStatus,
                          int round,
                          long operatorId,
                          String bizNo) {
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean commit(Command command) {
        int escrowChanged = walletRepository.casEscrowStatus(
                command.campusId(),
                command.errandId(),
                EscrowOrder.EscrowStatus.HELD,
                EscrowOrder.EscrowStatus.REFUNDED);
        if (escrowChanged == 0) {
            return false;
        }

        if (errandRepository.casCancel(command.errandId(), command.expectedVersion()) == 0) {
            throw new BusinessException(
                    ErrorCode.REFUND_CONFLICT,
                    "cancel cas failed, errandId=" + command.errandId());
        }
        errandRepository.appendStatusLog(
                command.errandId(),
                command.sourceStatus(),
                ErrandStatus.CANCELLED,
                command.round(),
                command.operatorId());

        EscrowOrder escrow = walletRepository
                .findEscrowByErrandId(command.campusId(), command.errandId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ESCROW_NOT_FOUND,
                        "errandId=" + command.errandId()));
        WalletAccount escrowAccount = walletRepository
                .findByOwner(ESCROW_OWNER_ID, AccountType.ESCROW)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACCOUNT_NOT_FOUND,
                        "escrow account"));
        WalletAccount publisherAccount = walletRepository
                .findByOwner(command.publisherId(), AccountType.USER)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACCOUNT_NOT_FOUND,
                        "publisher=" + command.publisherId()));

        Money amount = escrow.amount();
        if (walletRepository.casDebit(escrowAccount.id(), amount) == 0) {
            throw new BusinessException(ErrorCode.REFUND_CONFLICT, "escrow debit failed");
        }
        if (walletRepository.casCredit(publisherAccount.id(), amount) == 0) {
            throw new BusinessException(ErrorCode.REFUND_CONFLICT, "publisher credit failed");
        }

        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(),
                command.bizNo(),
                escrowAccount.id(),
                command.publisherId(),
                LedgerEntry.Direction.DEBIT,
                amount,
                escrowAccount.available().minus(amount),
                LedgerEntry.RefType.REFUND,
                command.errandId()));
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(),
                command.bizNo(),
                publisherAccount.id(),
                command.publisherId(),
                LedgerEntry.Direction.CREDIT,
                amount,
                publisherAccount.available().plus(amount),
                LedgerEntry.RefType.REFUND,
                command.errandId()));
        return true;
    }
}
