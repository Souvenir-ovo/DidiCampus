package com.didicampus.domain;

import com.didicampus.domain.wallet.model.AccountType;
import com.didicampus.domain.wallet.model.EscrowOrder;
import com.didicampus.domain.wallet.model.WalletAccount;
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalletModelTest {

    @Test
    void accountTransferUpdatesSnapshotAndVersion() {
        WalletAccount account = new WalletAccount(
                1, 1001, AccountType.USER,
                Money.fromCents(1000), Money.ZERO, 4
        );

        account.transferOut(Money.fromCents(250));
        account.transferIn(Money.fromCents(80));

        assertEquals(830, account.available().cents());
        assertEquals(830, account.total().cents());
        assertEquals(6, account.version());
    }

    @Test
    void insufficientBalanceIsRejected() {
        WalletAccount account = new WalletAccount(
                1, 1001, AccountType.USER,
                Money.fromCents(100), Money.ZERO, 0
        );

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> account.transferOut(Money.fromCents(101))
        );
        assertEquals(ErrorCode.INSUFFICIENT_BALANCE, error.code());
    }

    @Test
    void escrowHasOneWayTerminalTransitions() {
        EscrowOrder order = EscrowOrder.held(
                1, 10, 100, 1001, Money.fromCents(500)
        );

        assertEquals(EscrowOrder.EscrowStatus.RELEASED, order.release().status());
        assertEquals(EscrowOrder.EscrowStatus.REFUNDED, order.refund().status());
    }
}
