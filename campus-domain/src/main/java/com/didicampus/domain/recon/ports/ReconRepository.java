package com.didicampus.domain.recon.ports;

import java.time.LocalDate;
import java.util.List;

public interface ReconRepository {

    long debitMinusCredit();

    List<AccountDiff> findSnapshotDiffs();

    List<EscrowDiff> findEscrowClosureDiffs();

    void recordDiff(LocalDate date, String checkType, String subject,
                    Long expected, Long actual, String detail);

    int countDiffs(LocalDate date);

    record AccountDiff(long accountId, long ownerId,
                       long snapshotTotal, long ledgerNet) {}

    record EscrowDiff(long errandId, String escrowStatus, String reason) {}
}
