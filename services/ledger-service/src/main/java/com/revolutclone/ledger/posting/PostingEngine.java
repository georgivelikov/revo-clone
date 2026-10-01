package com.revolutclone.ledger.posting;

import com.revolutclone.ledger.chaos.ChaosLedgerState;
import com.revolutclone.messaging.api.TransientMessagingException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * The most important class in the project. Everything downstream of it assumes it is right.
 *
 * <h2>Accounting sign convention</h2>
 *
 * <p><b>A user wallet is a liability of the bank to the customer. A CREDIT increases the
 * customer's money; a DEBIT decreases it.</b> So a transfer from Alice to Bob is
 * {@code DEBIT alice_wallet} / {@code CREDIT bob_wallet}. Getting this backwards is the single
 * most common bug in ledger implementations, and it is invisible until you look at a statement.
 * See {@link com.revolutclone.ledger.domain.PostingDirection}.
 *
 * <h2>Balance column semantics</h2>
 *
 * <p>{@code account_balance} has two columns and postings and reservations both move money
 * between them. Which column a posting moves depends on whether it is capturing a hold, and
 * leaving that implicit is the single easiest way to corrupt this service:
 *
 * <pre>
 * | Posting                              | Balance effect                                  |
 * |---------------------------------------|--------------------------------------------------|
 * | DEBIT, reservation_id IS NULL        | available -= amount                             |
 * | CREDIT, reservation_id IS NULL       | available += amount                             |
 * | DEBIT, reservation_id set            | reserved -= amount ONLY - the money already left|
 * |                                       | available at hold time                          |
 * </pre>
 *
 * <p>The third row is the one that matters. If a capture's posting also decremented
 * {@code available}, hold-then-capture would take the money out twice: once at hold, once at
 * the posting. This failure is <b>invisible to the zero-sum invariant</b> - the postings
 * themselves still balance perfectly, so the headline safety check of this whole phase passes
 * while every reserved payment quietly destroys money in the balance cache. See
 * {@link com.revolutclone.ledger.domain.AccountBalance}'s mutation methods, which
 * {@link PostingTransactionExecutor} calls rather than mutating the columns inline, so this
 * table has exactly one implementation.
 *
 * <p>Validates <em>before</em> inserting anything, and the database re-checks at commit
 * regardless (the deferred {@code posting_balanced} trigger from
 * {@code V4__balanced_trigger.sql}) - application code eventually gets bypassed by a migration,
 * a support script, or a bug, and the trigger is what actually holds when that happens.
 *
 * <h2>Optimistic-lock retry (task 02.5)</h2>
 *
 * <p>{@link #postEntry} is the caller-facing entry point and is {@code @Retryable} on
 * {@link ObjectOptimisticLockingFailureException} - the exception JPA's optimistic locking on
 * {@code account_balance.version} throws when two concurrent entries touch the same account.
 * The actual transactional work lives on the separate {@link PostingTransactionExecutor} bean,
 * not a second method here: {@code @Retryable} and {@code @Transactional} are both proxy-based
 * AOP, and a call from one annotated method to another annotated method on the SAME bean bypasses
 * the callee's proxy entirely (self-invocation). Splitting them onto two beans means both
 * proxies actually fire - the retry interceptor wraps this method, the transaction interceptor
 * wraps {@link PostingTransactionExecutor#postEntry}. See the phase's own Risks &amp; gotchas:
 * "The retry must wrap outside the transaction, or you retry inside a transaction already marked
 * rollback-only." Retries are capped at 3 attempts with a small exponential backoff (20ms, then
 * 40ms), per "retry the whole operation up to 3 times with a small backoff" in
 * plans/phase-02-ledger.md's Balance maintenance and concurrency section. Each retry increments
 * the {@code ledger.optimistic.retries} counter ({@link PostingRetryMetrics}) rather than logging at
 * WARN, so a concurrency test's output stays readable.
 *
 * <h2>Chaos hooks (task 07.6)</h2>
 *
 * <p>{@code ledger.delay} and {@code ledger.fail-rate} are applied here, before
 * {@link PostingTransactionExecutor} is ever called - deliberately outside both the
 * {@code @Retryable} guard above and the transaction {@link PostingTransactionExecutor#postEntry}
 * opens. A delay here does not hold a database transaction open, and the injected
 * {@link TransientMessagingException} is a different exception type than
 * {@link ObjectOptimisticLockingFailureException}, so {@code @Retryable} never intercepts it: it
 * propagates out of the Kafka listener that called this method, where {@code DefaultErrorHandler}
 * classifies it {@code TRANSIENT} and routes it into the retry-topic chain, exactly like a real
 * transient failure would. {@code ledger.reject-all} is instead applied inside
 * {@link PostingTransactionExecutor}, because forcing a rejection must still emit
 * {@code EntryRejectedV1} through the outbox - see that class.
 *
 * <h2>Metrics (plans/phase-08-load-and-observability.md task 3)</h2>
 *
 * <p>{@code ledger.entries.posted{type}} and {@code ledger.entries.rejected{reason}} are counted
 * here rather than in {@link PostingTransactionExecutor}, so a chaos-injected transient failure
 * (thrown before the executor is even called) correctly counts as neither - it is a failed
 * attempt that gets retried, not a settled outcome. {@code ledger.posting.duration} times every
 * attempt that reaches the executor and returns normally (a thrown exception, transient or an
 * optimistic-lock conflict, is excluded the same way - it is not "how long a posting took", it is
 * "how long until this attempt gave up").
 */
@Service
public class PostingEngine {

    private final PostingTransactionExecutor executor;
    private final ChaosLedgerState chaos;
    private final MeterRegistry meterRegistry;
    private final Timer postingDurationTimer;

    public PostingEngine(PostingTransactionExecutor executor, ChaosLedgerState chaos, MeterRegistry meterRegistry) {
        this.executor = executor;
        this.chaos = chaos;
        this.meterRegistry = meterRegistry;
        this.postingDurationTimer = Timer.builder("ledger.posting.duration")
                .description("Latency of one PostingTransactionExecutor.postEntry call that returned normally")
                // plans/phase-08-load-and-observability.md's dashboard needs p50/p95/p99, not just
                // the mean (sum/count) and max a plain Timer exposes - this publishes the bucket
                // series histogram_quantile() needs.
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3,
            backoff = @Backoff(delay = 20, multiplier = 2), listeners = "postingRetryMetrics")
    public PostEntryResult postEntry(PostEntryCommand command) {
        chaos.applyDelay();
        if (chaos.shouldFailTransiently()) {
            throw new TransientMessagingException(
                    "chaos: ledger.fail-rate injected a transient failure for idempotencyKey="
                            + command.idempotencyKey());
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        PostEntryResult result = executor.postEntry(command);
        sample.stop(postingDurationTimer);
        recordOutcome(result);
        return result;
    }

    private void recordOutcome(PostEntryResult result) {
        switch (result) {
            case PostEntryResult.Posted posted ->
                meterRegistry.counter("ledger.entries.posted", "type", posted.entry().getType().name()).increment();
            case PostEntryResult.Rejected rejected ->
                meterRegistry.counter("ledger.entries.rejected", "reason", rejected.reason().name()).increment();
        }
    }
}
