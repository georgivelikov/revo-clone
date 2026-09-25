package com.revolutclone.ledger.reconciliation;

import com.revolutclone.contracts.events.LedgerDriftDetectedV1;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.messaging.api.OutboxAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * plans/phase-02-ledger.md's {@code ### Reconciliation}: nightly (plus an admin trigger),
 * recomputes every {@code ledger_account}'s balance from {@code sum(postings)} and compares
 * against the cached {@code account_balance} total. Any drift is logged at ERROR, published as
 * {@link LedgerDriftDetectedV1}, and counted on a Micrometer counter rather than logged per-row
 * at WARN - the same "count it, don't spam the log" discipline task 02.5's
 * {@code PostingRetryMetrics} already established for optimistic-lock retries.
 *
 * <p>"A safety net you have never seen fire is not a safety net" - the deliberate-drift admin
 * endpoint ({@code AdminController.corruptBalance}) exists specifically so this job's alerting
 * path can be watched actually working, not just assumed to.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final String OUTBOX_TOPIC = "ledger.entry.v1";
    static final String DRIFT_METRIC_NAME = "ledger.reconciliation.drift";

    private final AccountBalanceRepository accountBalanceRepository;
    private final OutboxAppender outboxAppender;
    private final Counter driftCounter;

    public ReconciliationService(AccountBalanceRepository accountBalanceRepository, OutboxAppender outboxAppender,
            MeterRegistry meterRegistry) {
        this.accountBalanceRepository = accountBalanceRepository;
        this.outboxAppender = outboxAppender;
        this.driftCounter = Counter.builder(DRIFT_METRIC_NAME)
                .description("Accounts found with a cached balance disagreeing with sum(postings)")
                .register(meterRegistry);
    }

    @Scheduled(cron = "${revolut.ledger.reconciliation.cron:0 0 2 * * *}")
    public void runScheduledReconciliation() {
        reconcile();
    }

    /**
     * One reconciliation pass: every drifted account is logged, published, and counted, all in
     * one transaction so the outbox rows this appends are atomic with having observed the drift.
     *
     * @return the drifted accounts found, for the admin trigger endpoint to report back
     */
    @Transactional
    public List<AccountBalanceRepository.DriftRow> reconcile() {
        List<AccountBalanceRepository.DriftRow> drifted = accountBalanceRepository.findDriftedAccounts();
        for (AccountBalanceRepository.DriftRow row : drifted) {
            log.error(
                    "ledger drift detected: account={} computedFromPostings={} cachedAvailablePlusReserved={} currency={}",
                    row.getAccountId(), row.getComputedMinor(), row.getCachedMinor(), row.getCurrency());
            outboxAppender.append(OUTBOX_TOPIC, row.getAccountId().toString(),
                    new LedgerDriftDetectedV1(row.getAccountId(), row.getComputedMinor(), row.getCachedMinor(),
                            row.getCurrency()));
            driftCounter.increment();
        }
        return drifted;
    }
}
