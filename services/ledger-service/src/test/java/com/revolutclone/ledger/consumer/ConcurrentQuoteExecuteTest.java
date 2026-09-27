package com.revolutclone.ledger.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.FxExecutionRequestedV1;
import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntry;
import com.revolutclone.ledger.domain.JournalEntryRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.Posting;
import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.ledger.domain.PostingRepository;
import com.revolutclone.ledger.domain.Reservation;
import com.revolutclone.ledger.domain.ReservationRepository;
import com.revolutclone.ledger.domain.ReservationState;
import com.revolutclone.ledger.domain.SystemAccountRefs;
import com.revolutclone.ledger.posting.PostEntryCommand;
import com.revolutclone.ledger.posting.PostEntryResult;
import com.revolutclone.ledger.posting.PostingEngine;
import com.revolutclone.ledger.posting.PostingLine;
import com.revolutclone.ledger.reservation.ReservationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-04-fx-multicurrency.md's Tests table: "Same quote executed twice concurrently → one
 * entry."
 *
 * <p>Asserted where "one entry" is a fact rather than an intention. fx-service makes the first cut
 * - its {@code ACTIVE -> CONSUMED} transition is a conditional update, so only one of two
 * concurrent executes publishes anything - but that guard protects one row in one database, and it
 * is not what stands between a redelivered or republished event and a second exchange. The ledger's
 * own {@code fxquote:<quoteId>} key is, and this drives the harsher case directly: the same
 * execution handled twice <em>at the same time</em>, with no {@code processed_event} row in the
 * way, which is what a rebalancing consumer group can genuinely produce.
 *
 * <p>The losing thread is allowed to fail. Two transactions inserting one unique
 * {@code idempotency_key} means one of them is rolled back by the database, which in production is
 * a redelivery and here is simply an exception - what must never happen is <em>two entries</em>, or
 * one entry with the money moved twice. The final sequential delivery proves the retry that
 * follows converges instead of posting a second time.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        "revolut.ledger.reservation.sweep-delay-ms=300000"
})
class ConcurrentQuoteExecuteTest {

    private static final long SELL_EUR = 10_000L;
    private static final long POOL_MID_GBP = 8_600L;
    private static final long CLIENT_GBP = 8_557L;
    private static final long FEE_GBP = 43L;
    private static final long FUNDING_EUR = 50_000L;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    @Autowired
    private FxExecutionRequestedEventHandler handler;
    @Autowired
    private ReservationService reservations;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private LedgerAccountRepository ledgerAccounts;
    @Autowired
    private AccountBalanceRepository balances;
    @Autowired
    private JournalEntryRepository journalEntries;
    @Autowired
    private PostingRepository postings;
    @Autowired
    private PostingEngine postingEngine;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void oneExecutionHandledTwiceAtOnceProducesExactlyOneEntryAndMovesTheMoneyOnce() throws Exception {
        UUID eurWallet = openWallet("EUR");
        UUID gbpWallet = openWallet("GBP");
        fund(eurWallet, "EUR", FUNDING_EUR);
        Reservation hold = reservations.hold(eurWallet, SELL_EUR, "EUR", UUID.randomUUID(),
                "fx-quote:" + UUID.randomUUID(), Instant.now().plus(Duration.ofSeconds(30)));

        UUID quoteId = UUID.randomUUID();
        EventEnvelope<FxExecutionRequestedV1> event = envelope(new FxExecutionRequestedV1(quoteId,
                UUID.randomUUID(), hold.getId(), gbpWallet, "EUR", SELL_EUR, "GBP", POOL_MID_GBP,
                CLIENT_GBP, FEE_GBP, 0L));

        AtomicInteger failures = new AtomicInteger();
        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<?>[] both = new CompletableFuture<?>[2];
            for (int i = 0; i < both.length; i++) {
                both[i] = CompletableFuture.runAsync(() -> {
                    try {
                        bothReady.await(10, TimeUnit.SECONDS);
                        handler.handle(event);
                    } catch (RuntimeException ex) {
                        // A unique-key or optimistic-lock failure on the losing side is the database
                        // doing its job; in production the message is redelivered and finds the
                        // entry already there.
                        failures.incrementAndGet();
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }, executor);
            }
            CompletableFuture.allOf(both).get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(failures.get()).as("at most one of the two may fail - both failing would mean "
                + "nothing was posted at all").isLessThanOrEqualTo(1);

        JournalEntry entry = journalEntries.findByIdempotencyKey("fxquote:" + quoteId).orElseThrow();
        assertThat(entry.getType()).isEqualTo(JournalEntryType.FX);
        assertThat(entriesFor(quoteId)).as("one entry, whatever the interleaving").isEqualTo(1L);

        List<Posting> legs = postings.findByEntryId(entry.getId());
        assertThat(legs).hasSize(5);

        // The money moved once. A second entry would be caught above; this catches the subtler
        // failure of one entry whose balance updates were applied twice.
        AccountBalance eur = balances.findById(eurWallet).orElseThrow();
        assertThat(eur.getAvailableMinor()).isEqualTo(FUNDING_EUR - SELL_EUR);
        assertThat(eur.getReservedMinor()).isZero();
        assertThat(balances.findById(gbpWallet).orElseThrow().getAvailableMinor()).isEqualTo(CLIENT_GBP);
        assertThat(reservationRepository.findById(hold.getId()).orElseThrow().getState())
                .isEqualTo(ReservationState.CAPTURED);

        // The redelivery that follows a rolled-back attempt: it must find the entry and stop, not
        // capture a reservation that is no longer HELD and not post a second time.
        handler.handle(event);
        assertThat(entriesFor(quoteId)).isEqualTo(1L);
        assertThat(balances.findById(gbpWallet).orElseThrow().getAvailableMinor()).isEqualTo(CLIENT_GBP);
    }

    private long entriesFor(UUID quoteId) {
        return journalEntries.findAll().stream()
                .filter(entry -> ("fxquote:" + quoteId).equals(entry.getIdempotencyKey()))
                .count();
    }

    private UUID openWallet(String currency) {
        return transactionTemplate.execute(status -> {
            LedgerAccount account = new LedgerAccount(UUID.randomUUID(), "user:" + UUID.randomUUID(),
                    LedgerAccountType.USER_WALLET, currency, UUID.randomUUID());
            ledgerAccounts.save(account);
            balances.save(new AccountBalance(account.getId(), currency));
            return account.getId();
        });
    }

    private void fund(UUID walletId, String currency, long amountMinor) {
        UUID clearing = ledgerAccounts.findByOwnerRefAndTypeAndCurrency(SystemAccountRefs.EXTERNAL_CLEARING,
                        LedgerAccountType.EXTERNAL_CLEARING, currency)
                .map(LedgerAccount::getId)
                .orElseThrow(() -> new AssertionError("no external-clearing account for " + currency));
        PostEntryResult result = postingEngine.postEntry(new PostEntryCommand("fund:" + UUID.randomUUID(),
                JournalEntryType.ADJUSTMENT, UUID.randomUUID(), List.of(
                        new PostingLine(clearing, PostingDirection.DEBIT, amountMinor, currency, null),
                        new PostingLine(walletId, PostingDirection.CREDIT, amountMinor, currency, null))));
        assertThat(result).isInstanceOf(PostEntryResult.Posted.class);
    }

    private static EventEnvelope<FxExecutionRequestedV1> envelope(FxExecutionRequestedV1 payload) {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), "corr-" + payload.quoteId(),
                "FxExecutionRequestedV1", 1, payload);
    }
}
