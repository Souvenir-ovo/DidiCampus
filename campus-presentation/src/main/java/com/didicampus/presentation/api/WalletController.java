package com.didicampus.presentation.api;

import com.didicampus.domain.wallet.ports.WalletQueryPort;
import com.didicampus.application.usecase.QueryWalletService;
import com.didicampus.presentation.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final QueryWalletService walletQuery;

    public WalletController(QueryWalletService walletQuery) {
        this.walletQuery = walletQuery;
    }

    @GetMapping
    public ApiResponse<WalletQueryPort.BalanceView> balance() {
        return ApiResponse.ok(walletQuery.balance(CurrentUser.id()));
    }

    @GetMapping("/ledger")
    public ApiResponse<List<WalletQueryPort.LedgerView>> ledger(@RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(walletQuery.ledger(CurrentUser.id(), page, size));
    }
}
