package com.didicampus.domain.wallet.model;

import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.Money;

import java.util.Objects;

/**
 * 钱包余额快照。余额便于快速读取，流水负责追溯，后续对账任务校验二者一致。
 */
public final class WalletAccount {

    private final long id;
    private final long ownerId;
    private final AccountType type;
    private Money available;
    private Money frozen;
    private long version;

    public WalletAccount(long id, long ownerId, AccountType type,
                         Money available, Money frozen, long version) {
        this.id = id;
        this.ownerId = ownerId;
        this.type = Objects.requireNonNull(type, "type");
        this.available = Objects.requireNonNull(available, "available");
        this.frozen = Objects.requireNonNull(frozen, "frozen");
        this.version = version;
    }

    public void transferOut(Money amount) {
        Objects.requireNonNull(amount, "amount");
        if (available.compareTo(amount) < 0) {
            throw new BusinessException(
                    ErrorCode.INSUFFICIENT_BALANCE,
                    "available=" + available + ", required=" + amount
            );
        }
        available = available.minus(amount);
        version++;
    }

    public void transferIn(Money amount) {
        available = available.plus(Objects.requireNonNull(amount, "amount"));
        version++;
    }

    public Money total() {
        return available.plus(frozen);
    }

    public long id() { return id; }
    public long ownerId() { return ownerId; }
    public AccountType type() { return type; }
    public Money available() { return available; }
    public Money frozen() { return frozen; }
    public long version() { return version; }
}
