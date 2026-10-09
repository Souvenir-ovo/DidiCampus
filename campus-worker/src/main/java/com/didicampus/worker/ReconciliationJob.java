package com.didicampus.worker;

import com.didicampus.domain.recon.ports.ReconRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconRepository reconRepository;

    public ReconciliationJob(ReconRepository reconRepository) {
        this.reconRepository = reconRepository;
    }

    @Scheduled(cron = "${didicampus.worker.recon.cron:0 0 2 * * ?}")
    public void runDaily() {
        run(LocalDate.now());
    }

    public int run(LocalDate date) {
        long balanceDelta = reconRepository.debitMinusCredit();
        int diffCount = 0;

        if (balanceDelta != 0L) {
            reconRepository.recordDiff(date, "DEBIT_CREDIT", "GLOBAL", 0L, balanceDelta,
                    "debit minus credit = " + balanceDelta);
            diffCount++;
        }

        for (ReconRepository.AccountDiff diff : reconRepository.findSnapshotDiffs()) {
            reconRepository.recordDiff(date, "SNAPSHOT", String.valueOf(diff.accountId()),
                    diff.snapshotTotal(), diff.ledgerNet(),
                    "owner=" + diff.ownerId() + ", snapshot=" + diff.snapshotTotal()
                            + ", ledger=" + diff.ledgerNet());
            diffCount++;
        }

        for (ReconRepository.EscrowDiff diff : reconRepository.findEscrowClosureDiffs()) {
            reconRepository.recordDiff(date, "ESCROW_CLOSURE", String.valueOf(diff.errandId()),
                    null, null, diff.reason());
            diffCount++;
        }

        if (diffCount > 0) {
            log.warn("reconciliation finished with {} diff(s), date={}", diffCount, date);
        } else {
            log.info("reconciliation finished cleanly, date={}", date);
        }
        return diffCount;
    }
}
