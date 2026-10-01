package com.revolutclone.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.revolutclone.ledger.chaos.ChaosLedgerState;
import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.JournalEntry;
import com.revolutclone.ledger.domain.JournalEntryType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * plans/phase-02-ledger.md's Balance maintenance and concurrency section: "retry the whole
 * operation up to 3 times with a small backoff." This tests {@link PostingEngine}'s
 * {@code @Retryable} proxy in isolation - {@link PostingTransactionExecutor} is replaced with a
 * Mockito mock whose behavior is programmed deterministically (throw N times, then succeed),
 * rather than trying to provoke a real database version conflict via thread races. A genuine
 * concurrent conflict is inherently timing-dependent with only two or three threads - the
 * database will not reliably interleave two transactions into the narrow read/write window
 * needed to provoke {@link ObjectOptimisticLockingFailureException} on demand, which is exactly
 * why the phase pushes real concurrency proof to {@code ConcurrentDebitTest}/
 * {@code ConcurrentMixedTest} (task 02.9, at 100-thread scale, run 50 times in a loop for
 * flakiness). What THIS test can and must prove deterministically is that the wrapper itself is
 * wired correctly: {@code @Retryable} actually intercepts the call, actually retries on the
 * right exception type, actually gives up after {@code maxAttempts}, and actually counts each
 * retry via {@link PostingRetryMetrics} - see the phase's Risks &amp; gotchas: "Optimistic lock
 * retries under the concurrency test will look like failures in the logs. Count them as a metric
 * rather than logging each at WARN."
 */
@SpringJUnitConfig(PostingEngineRetryWrapperTest.RetryWrapperTestConfig.class)
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
class PostingEngineRetryWrapperTest {

    @Autowired
    private PostingEngine postingEngine;
    @Autowired
    private PostingTransactionExecutor executor;
    @Autowired
    private PostingRetryMetrics retryMetrics;

    @Test
    void recoversFromASingleOptimisticLockConflictAndReturnsTheEventualResult() {
        PostEntryCommand command = command();
        PostEntryResult.Posted expected = postedResult(command);

        when(executor.postEntry(command))
                .thenThrow(new ObjectOptimisticLockingFailureException(AccountBalance.class, "acc-1"))
                .thenReturn(expected);

        PostEntryResult result = postingEngine.postEntry(command);

        assertThat(result).isSameAs(expected);
        verify(executor, times(2)).postEntry(command);
        assertThat(retryMetrics.retryCount()).isEqualTo(1L);
    }

    @Test
    void givesUpAfterThreeAttemptsAndPropagatesTheConflict() {
        PostEntryCommand command = command();
        when(executor.postEntry(command))
                .thenThrow(new ObjectOptimisticLockingFailureException(AccountBalance.class, "acc-2"));

        assertThatThrownBy(() -> postingEngine.postEntry(command))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // maxAttempts = 3: the original attempt plus two retries, never a fourth.
        // RetryListener.onError fires after EVERY failed attempt, including the last one that
        // exhausts retries - so the counter is 3, not 2 (verified by this test failing red
        // first with the wrong expectation).
        verify(executor, times(3)).postEntry(command);
        assertThat(retryMetrics.retryCount()).isEqualTo(3L);
    }

    @Test
    void aSuccessfulFirstAttemptNeverRetries() {
        PostEntryCommand command = command();
        PostEntryResult.Posted expected = postedResult(command);
        when(executor.postEntry(command)).thenReturn(expected);

        PostEntryResult result = postingEngine.postEntry(command);

        assertThat(result).isSameAs(expected);
        verify(executor, times(1)).postEntry(command);
        assertThat(retryMetrics.retryCount()).isZero();
    }

    private static PostEntryCommand command() {
        UUID account = UUID.randomUUID();
        UUID counterparty = UUID.randomUUID();
        return new PostEntryCommand("payment:" + UUID.randomUUID(), JournalEntryType.TRANSFER, UUID.randomUUID(),
                List.of(
                        new PostingLine(account, com.revolutclone.ledger.domain.PostingDirection.DEBIT, 500, "EUR",
                                null),
                        new PostingLine(counterparty, com.revolutclone.ledger.domain.PostingDirection.CREDIT, 500,
                                "EUR", null)));
    }

    private static PostEntryResult.Posted postedResult(PostEntryCommand command) {
        JournalEntry entry = new JournalEntry(UUID.randomUUID(), command.idempotencyKey(), command.type(),
                command.referenceId(), null, null);
        return new PostEntryResult.Posted(entry, List.of());
    }

    @Configuration
    @EnableRetry
    static class RetryWrapperTestConfig {

        @Bean
        PostingTransactionExecutor postingTransactionExecutor() {
            return mock(PostingTransactionExecutor.class);
        }

        @Bean
        PostingEngine postingEngine(PostingTransactionExecutor executor, MeterRegistry meterRegistry) {
            return new PostingEngine(executor, new ChaosLedgerState(), meterRegistry);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean("postingRetryMetrics")
        PostingRetryMetrics postingRetryMetrics(MeterRegistry meterRegistry) {
            return new PostingRetryMetrics(meterRegistry);
        }
    }
}
