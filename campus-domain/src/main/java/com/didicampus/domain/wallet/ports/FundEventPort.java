package com.didicampus.domain.wallet.ports;

public interface FundEventPort {

    boolean publishInTransaction(FundEvent event, LocalWork localWork);

    record FundEvent(String bizNo, String type, long errandId,
                     long publisherId, long runnerId,
                     long amountCents, long commissionCents) {}

    @FunctionalInterface
    interface LocalWork {
        boolean execute();
    }
}
