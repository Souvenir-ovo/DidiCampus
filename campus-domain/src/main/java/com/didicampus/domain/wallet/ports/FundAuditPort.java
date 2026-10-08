package com.didicampus.domain.wallet.ports;

public interface FundAuditPort {

    void record(String bizNo, String action, long errandId, long operatorId,
                String detailJson, boolean success, String message);
}
