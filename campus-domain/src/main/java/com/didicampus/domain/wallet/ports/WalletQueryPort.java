package com.didicampus.domain.wallet.ports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WalletQueryPort {

    Optional<BalanceView> findBalance(long ownerId);

    List<LedgerView> ledger(long ownerId, int page, int size);

    record BalanceView(long availableCents, long frozenCents) {}

    record LedgerView(Instant time, String direction, long amountCents,
                      String refType, long refId, String bizNo) {}
}
