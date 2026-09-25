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
 * plans/phase-02-ledger.md's {@code ## Tests} table: "Parallel debits and credits on one
 * account -&gt; final balance equals the arithmetic sum." Each thread gets its OWN counterparty
 * account (never shared across threads), so all lock contention from
 * {@link PostingEngine#postEntry}'s optimistic-lock retry wrapper (task 02.5) falls on the one
 * account under test - a thread's success or failure is never influenced by contention on some
 * other thread's counterparty. The starting balance is sized generously so no debit can be
 * legitimately rejected for {@code INSUFFICIENT_FUNDS}; the only way a call fails here is
 * retry-exhaustion under contention, exactly like {@link ConcurrentDebitTest}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class ConcurrentMixedTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    private static final int THREAD_COUNT = 60;
    private static final long STARTING_BALANCE = 1_000_000; // 10,000.00 EUR - generous headroom
    private static final long AMOUNT_PER_OP = 1_000; // 10.00 EUR

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private AccountBalanceRepository accountBalanceRepository;
    @Autowired
    private PostingEngine postingEngine;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void finalBalanceEqualsTheArithmeticSumOfSuccessfulOperations() throws Exception {
        UUID mainAccount = openFundedWallet(STARTING_BALANCE);

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
        AtomicLong signedSum = new AtomicLong(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger lockExhaustionCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        try {
            List<Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < THREAD_COUNT; i++) {
                PostingDirection direction = (i % 2 == 0) ? PostingDirection.DEBIT : PostingDirection.CREDIT;
                UUID counterparty = openFundedWallet(STARTING_BALANCE);
                tasks.add(() -> {
                    barrier.await();
                    PostEntryCommand command = new PostEntryCommand("payment:" + UUID.randomUUID(),
                            JournalEntryType.TRANSFER, UUID.randomUUID(),
                            direction == PostingDirection.DEBIT
                                    ? List.of(
                                            new PostingLine(mainAccount, PostingDirection.DEBIT, AMOUNT_PER_OP, "EUR",
                                                    null),
                                            new PostingLine(counterparty, PostingDirection.CREDIT, AMOUNT_PER_OP,
                                                    "EUR", null))
                                    : List.of(
                                            new PostingLine(counterparty, PostingDirection.DEBIT, AMOUNT_PER_OP,
                                                    "EUR", null),
                                            new PostingLine(mainAccount, PostingDirection.CREDIT, AMOUNT_PER_OP,
                                                    "EUR", null)));
                    try {
                        PostEntryResult result = postingEngine.postEntry(command);
                        if (result instanceof PostEntryResult.Posted) {
                            successCount.incrementAndGet();
                            long signed = direction == PostingDirection.DEBIT ? -AMOUNT_PER_OP : AMOUNT_PER_OP;
                            signedSum.addAndGet(signed);
                        } else {
                            rejectedCount.incrementAndGet();
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

        long finalBalance = readBalance(mainAccount);
        long expected = STARTING_BALANCE + signedSum.get();

        assertThat(finalBalance)
                .as("final balance must equal starting balance plus the arithmetic sum of successful ops "
                        + "(successes=%d, rejected=%d, lockExhaustion=%d)",
                        successCount.get(), rejectedCount.get(), lockExhaustionCount.get())
                .isEqualTo(expected);
        assertThat(rejectedCount.get()).as("no debit should have been rejected for insufficient funds "
                + "given the generous starting balance").isZero();
        assertThat(successCount.get() + lockExhaustionCount.get()).isEqualTo(THREAD_COUNT);
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
