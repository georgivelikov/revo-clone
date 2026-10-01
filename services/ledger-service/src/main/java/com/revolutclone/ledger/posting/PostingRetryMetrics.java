package com.revolutclone.ledger.posting;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.stereotype.Component;

/**
 * Counts optimistic-lock retries as a Micrometer metric rather than relying on Spring Retry's
 * default WARN-level logging - see the phase's own Risks &amp; gotchas: "Optimistic lock retries
 * under the concurrency test will look like failures in the logs. Count them as a metric rather
 * than logging each at WARN, or the test output is unreadable." {@code onError} fires once per
 * failed attempt, i.e. once per retry (not on the eventual success or the final exhaustion), so
 * the counter's value after a burst of contention is exactly the number of retries that occurred
 * - phases 02.9's {@code ConcurrentDebitTest}/{@code ConcurrentMixedTest} read
 * {@link #retryCount()} to classify failures as lock-exhaustion vs. genuine rejection, per the
 * phase's instruction to report both counts in the test's assertion message.
 *
 * <p>Registered by name ({@code listeners = "postingRetryMetrics"}) on
 * {@link PostingEngine#postEntry}'s {@code @Retryable} rather than wired as a global
 * {@code RetryTemplate} listener, so it only observes this one retryable operation.
 */
@Component("postingRetryMetrics")
class PostingRetryMetrics implements RetryListener {

    // plans/phase-08-load-and-observability.md task 3's naming: ledger.optimistic.retries, not
    // this class's original ledger.posting.retry - the metric's registered name, not the
    // PostingRetryMetrics.retryCount() method tests call, so renaming it breaks nothing.
    static final String METRIC_NAME = "ledger.optimistic.retries";

    private final Counter retryCounter;

    PostingRetryMetrics(MeterRegistry meterRegistry) {
        this.retryCounter = Counter.builder(METRIC_NAME)
                .description("Optimistic-lock retries of PostingEngine.postEntry")
                .register(meterRegistry);
    }

    @Override
    public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback,
            Throwable throwable) {
        retryCounter.increment();
    }

    /**
     * Total retries observed since startup - exposed for tests, not just the metrics endpoint.
     * Cast to {@code long}: {@link Counter#count()} returns {@code double} per Micrometer's own
     * API (not a money value - the tripwire's blunt double/float rule flags any double-returning
     * method by shape, not by meaning), but every increment here is a whole retry, so the count
     * is always an exact integer and {@code long} is the more honest type for it.
     */
    long retryCount() {
        return (long) retryCounter.count();
    }
}
