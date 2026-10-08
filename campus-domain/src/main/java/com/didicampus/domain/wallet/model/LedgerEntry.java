package com.didicampus.domain.wallet.model;

import com.didicampus.shared.Money;

import java.util.Objects;

/**
 * 资金流水模型。每次资金动作由一条借方和一条贷方组成。
 */
public record LedgerEntry(long id, String bizNo, long accountId, long userId,
                          Direction direction, Money amount, Money balanceAfter,
                          RefType refType, long refId) {

    public LedgerEntry {
        if (bizNo == null || bizNo.isBlank()) {
            throw new IllegalArgumentException("资金业务号不能为空");
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        Objects.requireNonNull(refType, "refType");
    }

    public enum Direction {
        DEBIT,
        CREDIT
    }

    public enum RefType {
        ESCROW,
        SETTLE,
        REFUND,
        RECHARGE,
        WITHDRAW
    }

    public static String escrowBizNo(long errandId) {
        return "escrow:" + errandId;
    }

    public static String settleBizNo(long errandId) {
        return "settle:" + errandId;
    }

    public static String refundBizNo(long errandId) {
        return "refund:" + errandId;
    }
}
