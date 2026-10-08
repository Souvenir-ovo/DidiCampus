package com.didicampus.domain.wallet.ports;

import com.didicampus.domain.wallet.model.AccountType;
import com.didicampus.domain.wallet.model.EscrowOrder;
import com.didicampus.domain.wallet.model.LedgerEntry;
import com.didicampus.domain.wallet.model.WalletAccount;
import com.didicampus.shared.Money;

import java.util.Optional;

public interface WalletRepository {

    Optional<WalletAccount> findByOwner(long ownerId, AccountType type);

    Optional<WalletAccount> findById(long accountId);

    int casDebit(long accountId, Money amount);

    int casCredit(long accountId, Money amount);

    void insertLedger(LedgerEntry entry);

    void insertEscrow(EscrowOrder order);

    boolean escrowExists(long campusId, long errandId);

    Optional<EscrowOrder> findEscrowByErrandId(long campusId, long errandId);

    int casEscrowStatus(long campusId, long errandId,
                        EscrowOrder.EscrowStatus from,
                        EscrowOrder.EscrowStatus to);

    boolean ledgerExists(String bizNo);
}
