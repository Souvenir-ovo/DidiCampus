package com.didicampus.domain.wallet.model;

import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.Money;

import java.util.Objects;

/**
 * 托管订单只允许从 HELD 去一个终态，防止同一笔资金同时结算和退款。
 */
public record EscrowOrder(long id, long campusId, long errandId, long publisherId,
                          Money amount, EscrowStatus status) {

    public EscrowOrder {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
    }

    public static EscrowOrder held(long id, long campusId, long errandId,
                                   long publisherId, Money amount) {
        return new EscrowOrder(id, campusId, errandId, publisherId, amount, EscrowStatus.HELD);
    }

    public EscrowOrder release() {
        ensureHeld("release");
        return new EscrowOrder(id, campusId, errandId, publisherId, amount, EscrowStatus.RELEASED);
    }

    public EscrowOrder refund() {
        ensureHeld("refund");
        return new EscrowOrder(id, campusId, errandId, publisherId, amount, EscrowStatus.REFUNDED);
    }

    private void ensureHeld(String action) {
        if (status != EscrowStatus.HELD) {
            throw new BusinessException(
                    ErrorCode.ESCROW_NOT_HELD,
                    "escrowId=" + id + ", status=" + status + ", action=" + action
            );
        }
    }

    public enum EscrowStatus {
        HELD,
        RELEASED,
        REFUNDED
    }
}
