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
import com.didicampus.shared.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仲裁支持发单人时的本地资金事务。
 *
 * <p>争议订单与普通取消订单的入口状态不同，因此单独保留
 * DISPUTED -> REFUNDED 的 CAS，避免把两种业务语义混在一起。</p>
 */
@Service
public class ArbitrateRefundCommitService {

    private static final long ESCROW_OWNER_ID = -1L;

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final SnowflakeIdGenerator idGenerator;

    public ArbitrateRefundCommitService(ErrandRepository errandRepository,
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

        if (errandRepository.casRefundFromDispute(
                command.errandId(), command.expectedVersion()) == 0) {
            throw new BusinessException(
                    ErrorCode.REFUND_CONFLICT,
                    "arbitration refund cas failed, errandId=" + command.errandId());
        }

        errandRepository.appendStatusLog(
                command.errandId(),
                ErrandStatus.DISPUTED,
                ErrandStatus.REFUNDED,
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

        if (walletRepository.casDebit(escrowAccount.id(), escrow.amount()) == 0) {
            throw new BusinessException(ErrorCode.REFUND_CONFLICT, "escrow debit failed");
        }
        if (walletRepository.casCredit(publisherAccount.id(), escrow.amount()) == 0) {
            throw new BusinessException(ErrorCode.REFUND_CONFLICT, "publisher credit failed");
        }

        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(),
                command.bizNo(),
                escrowAccount.id(),
                command.publisherId(),
                LedgerEntry.Direction.DEBIT,
                escrow.amount(),
                escrowAccount.available().minus(escrow.amount()),
                LedgerEntry.RefType.REFUND,
                command.errandId()));
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(),
                command.bizNo(),
                publisherAccount.id(),
                command.publisherId(),
                LedgerEntry.Direction.CREDIT,
                escrow.amount(),
                publisherAccount.available().plus(escrow.amount()),
                LedgerEntry.RefType.REFUND,
                command.errandId()));
        return true;
    }
}
