package com.didicampus.application.usecase;

import com.didicampus.domain.wallet.ports.WalletQueryPort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QueryWalletService {

    private static final int MAX_PAGE_SIZE = 50;

    private final WalletQueryPort queryPort;

    public QueryWalletService(WalletQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    public WalletQueryPort.BalanceView balance(long ownerId) {
        return queryPort.findBalance(ownerId)
                .orElse(new WalletQueryPort.BalanceView(0L, 0L));
    }

    public List<WalletQueryPort.LedgerView> ledger(long ownerId, int page, int size) {
        return queryPort.ledger(ownerId, Math.max(page, 0), cap(size));
    }

    private int cap(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
