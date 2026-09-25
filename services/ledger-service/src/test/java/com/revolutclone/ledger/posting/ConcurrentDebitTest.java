package com.revolutclone.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.PostingDirection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-02-ledger.md's {@code ## Tests} table: "100 threads debit 1.00 from an account
 * holding 50.00." The phase spec is explicit that the obvious assertion - "exactly 50 succeed,
 * 50 rejected" - is WRONG: with the task 02.5 retry wrapper capped at 3 attempts, some threads
 * legitimately exhaust their retries under 100-way contention and fail with a lock exception
 * rather than {@code INSUFFICIENT_FUNDS}. This test asserts only what the design actually
 * guarantees:
 *
 * <ul>
 *   <li>successes &le; 50, and successes == 50 only when no thread exhausted its retries;</li>
 *   <li>final balance == 50.00 &minus; successes &times; 1.00, exactly;</li>
 *   <li>{@code available_minor} never observed negative at any point;</li>
 *   <li>every failure classified as either {@code INSUFFICIENT_FUNDS} or lock-exhaustion, both
 *       counts reported in the assertion message.</li>
 * </ul>
 *
 * <p>Each thread debits from the ONE account under test but credits its own private counterparty
 * (never shared across threads), so all contention measured here is genuinely contention on the
 * account under test - not an artifact of counterparty lock contention.
 *
 * <p>Per the phase spec, this test is meant to be run 50 consecutive times as the actual gate for
 * this phase; that repetition is a manual verification practice, not encoded into the test itself.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class ConcurrentDebitTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    private static final int THREAD_COUNT = 100;
    private static final long AMOUNT_PER_DEBIT = 100; // 1.00 EUR
    private static final int MAX_SUCCESSES = 50;
    private static final long STARTING_BALANCE = MAX_SUCCESSES * AMOUNT_PER_DEBIT; // 50.00 EUR

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private AccountBalanceRepository accountBalanceRepository;
    @Autowired
    private PostingEngine postingEngine;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void hundredThreadsDebitingConcurrentlyNeverOverspendAndNeverGoNegative() throws Exception {
        UUID account = openFundedWallet(STARTING_BALANCE);

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);
        AtomicInteger lockExhaustionCount = new AtomicInteger(0);
        AtomicLong minObservedBalance = new AtomicLong(Long.MAX_VALUE);

        try {
            List<Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < THREAD_COUNT; i++) {
                UUID counterparty = openFundedWallet(0);
                tasks.add(() -> {
                    barrier.await();
                    PostEntryCommand command = new PostEntryCommand("payment:" + UUID.randomUUID(),
                            JournalEntryType.TRANSFER, UUID.randomUUID(),
                            List.of(
                                    new PostingLine(account, PostingDirection.DEBIT, AMOUNT_PER_DEBIT, "EUR", null),
                                    new PostingLine(counterparty, PostingDirection.CREDIT, AMOUNT_PER_DEBIT, "EUR",
                                            null)));
                    try {
                        PostEntryResult result = postingEngine.postEntry(command);
                        if (result instanceof PostEntryResult.Posted) {
                            successCount.incrementAndGet();
                            long balanceAfter = readBalance(account);
                            minObservedBalance.updateAndGet(current -> Math.min(current, balanceAfter));
                        } else if (result instanceof PostEntryResult.Rejected rejected
                                && rejected.reason() == EntryRejectionReason.INSUFFICIENT_FUNDS) {
                            insufficientFundsCount.incrementAndGet();
                        } else {
                            throw new AssertionError("unexpected rejection reason: " + result);
                        }
                    } catch (ObjectOptimisticLockingFailureException lockFailure) {
                        lockExhaustionCount.incrementAndGet();
                    }
                    return null;
                });
            }

            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    throw new AssertionError("worker thread threw unexpectedly", e.getCause());
                }
            }
        } finally {
            pool.shutdown();
        }

        long finalBalance = readBalance(account);
        int successes = successCount.get();
        int insufficientFunds = insufficientFundsCount.get();
        int lockExhaustion = lockExhaustionCount.get();
        String assertionContext = String.format(
                "successes=%d, insufficientFunds=%d, lockExhaustion=%d (total=%d, expected=%d)",
                successes, insufficientFunds, lockExhaustion,
                successes + insufficientFunds + lockExhaustion, THREAD_COUNT);

        assertThat(successes).as("successes must never exceed 50 (%s)", assertionContext)
                .isLessThanOrEqualTo(MAX_SUCCESSES);
        if (successes == MAX_SUCCESSES) {
            assertThat(lockExhaustion)
                    .as("if all 50 possible debits succeeded, no thread can have exhausted its retries (%s)",
                            assertionContext)
                    .isZero();
        }
        assertThat(finalBalance)
                .as("final balance must equal starting balance minus successes * amountPerDebit (%s)",
                        assertionContext)
                .isEqualTo(STARTING_BALANCE - (long) successes * AMOUNT_PER_DEBIT);
        assertThat(minObservedBalance.get())
                .as("available_minor must never be observed negative at any point (%s)", assertionContext)
                .isGreaterThanOrEqualTo(0L);
        assertThat(successes + insufficientFunds + lockExhaustion)
                .as("every one of the 100 threads must be classified as exactly one outcome (%s)", assertionContext)
                .isEqualTo(THREAD_COUNT);
    }

    private long readBalance(UUID accountId) {
        return transactionTemplate.execute(status -> accountBalanceRepository.findById(accountId).orElseThrow()
                .getAvailableMinor());
    }

    private UUID openFundedWallet(long startingAvailableMinor) {
        return transactionTemplate.execute(status -> {
            LedgerAccount account = new LedgerAccount(UUID.randomUUID(), "user:" + UUID.randomUUID(),
                    LedgerAccountType.USER_WALLET, "EUR");
            ledgerAccountRepository.save(account);
            AccountBalance balance = new AccountBalance(account.getId(), "EUR");
            balance.creditAvailable(startingAvailableMinor);
            accountBalanceRepository.save(balance);
            return account.getId();
        });
    }
}
