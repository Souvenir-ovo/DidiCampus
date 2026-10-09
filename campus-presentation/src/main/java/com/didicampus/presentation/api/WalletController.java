package com.didicampus.presentation.api;

import com.didicampus.domain.wallet.ports.WalletQueryPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private final WalletQueryPort walletQuery;

    public WalletController(WalletQueryPort walletQuery) {
        this.walletQuery = walletQuery;
    }

    @GetMapping("/{ownerId}/balance")
    public ApiResponse<WalletQueryPort.BalanceView> balance(@PathVariable long ownerId) {
        return ApiResponse.ok(walletQuery.findBalance(ownerId).orElse(null));
    }

    @GetMapping("/{ownerId}/ledger")
    public ApiResponse<List<WalletQueryPort.LedgerView>> ledger(@PathVariable long ownerId,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(walletQuery.ledger(ownerId, page, size));
    }
}
