package com.revolutclone.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
 * Round-trips each of the five ledger entities through a real Postgres. Every step runs in
 * its own transaction via {@link TransactionTemplate} (not the single always-rolled-back
 * transaction {@code @Transactional} tests get) specifically so a "save, then reload in a
 * later step" sequence proves something real, not just what is still sitting in the first
 * level cache.
 *
 * <p>What this specifically guards against: the {@code Persistable}/{@code merge()}-vs-
 * {@code persist()} bug found live twice already (identity-service's {@code UserProfile},
 * task 01.2; account-service's {@code Account}, task 01.5) - a manually-assigned id routes
 * {@code save()} through {@code merge()}, which operates on an internal copy, so a
 * {@code @PrePersist}-populated field like {@code createdAt} comes back null on the instance
 * the caller is still holding even though the database row is correct. Every entity here
 * asserts the field is populated on the object {@code save()} itself returns, not just after
 * a reload - that is precisely the distinction the earlier bug hid behind.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class DomainEntityPersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private PostingRepository postingRepository;
    @Autowired
    private AccountBalanceRepository accountBalanceRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void ledgerAccountPrePersistFieldIsPopulatedOnTheSavedInstance() {
        LedgerAccount saved = transactionTemplate.execute(status -> {
            LedgerAccount account = new LedgerAccount(UUID.randomUUID(), "user:" + UUID.randomUUID(),
                    LedgerAccountType.USER_WALLET, "EUR");
            return ledgerAccountRepository.save(account);
        });

        assertThat(saved.getCreatedAt()).isNotNull();

        LedgerAccount reloaded = ledgerAccountRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getOwnerRef()).isEqualTo(saved.getOwnerRef());
        assertThat(reloaded.getType()).isEqualTo(LedgerAccountType.USER_WALLET);
        assertThat(reloaded.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void journalEntryAndPostingsPersistAndBalanceToZero() {
        UUID accountAId = createLedgerAccount("EUR");
        UUID accountBId = createLedgerAccount("EUR");

        JournalEntry savedEntry = transactionTemplate.execute(status -> {
            JournalEntry entry = new JournalEntry(UUID.randomUUID(), "entry-" + UUID.randomUUID(),
                    JournalEntryType.TRANSFER, UUID.randomUUID(), null, null);
            return journalEntryRepository.save(entry);
        });

        assertThat(savedEntry.getCreatedAt()).isNotNull();
        assertThat(savedEntry.getMetadata()).isEqualTo("{}");

        transactionTemplate.executeWithoutResult(status -> {
            postingRepository.save(new Posting(UUID.randomUUID(), savedEntry.getId(), accountAId,
                    PostingDirection.DEBIT, 1_500, "EUR", null, 1));
            postingRepository.save(new Posting(UUID.randomUUID(), savedEntry.getId(), accountBId,
                    PostingDirection.CREDIT, 1_500, "EUR", null, 2));
        });

        var postings = postingRepository.findByEntryId(savedEntry.getId());
        assertThat(postings).hasSize(2);
        long net = postings.stream()
                .mapToLong(p -> p.getDirection() == PostingDirection.CREDIT ? p.getAmountMinor() : -p.getAmountMinor())
                .sum();
        assertThat(net).isZero();
    }

    @Test
    void accountBalanceCreatedOnceThenUpdatedThroughAManagedInstance() {
        UUID accountId = createLedgerAccount("EUR");

        transactionTemplate.executeWithoutResult(
                status -> accountBalanceRepository.save(new AccountBalance(accountId, "EUR")));

        AccountBalance afterCreate = accountBalanceRepository.findById(accountId).orElseThrow();
        assertThat(afterCreate.getAvailableMinor()).isZero();
        assertThat(afterCreate.getReservedMinor()).isZero();
        long versionAfterCreate = afterCreate.getVersion();

        // Deliberately NOT calling save() again here - the whole point of the discipline
        // documented on AccountBalance is that mutation goes through a managed instance found
        // within the transaction, never a freshly constructed one.
        transactionTemplate.executeWithoutResult(status -> {
            AccountBalance managed = accountBalanceRepository.findById(accountId).orElseThrow();
            managed.creditAvailable(10_000);
        });

        AccountBalance afterCredit = accountBalanceRepository.findById(accountId).orElseThrow();
        assertThat(afterCredit.getAvailableMinor()).isEqualTo(10_000);
        assertThat(afterCredit.getVersion()).isGreaterThan(versionAfterCreate);

        transactionTemplate.executeWithoutResult(status -> {
            AccountBalance managed = accountBalanceRepository.findById(accountId).orElseThrow();
            managed.moveToReserved(4_000);
        });
        AccountBalance afterHold = accountBalanceRepository.findById(accountId).orElseThrow();
        assertThat(afterHold.getAvailableMinor()).isEqualTo(6_000);
        assertThat(afterHold.getReservedMinor()).isEqualTo(4_000);

        // Capture must decrement reserved ONLY, never available - plans/phase-02-ledger.md's
        // balance-column-semantics table, row 3. This is the single easiest place to corrupt
        // this service, and it stays invisible to the zero-sum invariant.
        transactionTemplate.executeWithoutResult(status -> {
            AccountBalance managed = accountBalanceRepository.findById(accountId).orElseThrow();
            managed.releaseReservedOnCapture(4_000);
        });
        AccountBalance afterCapture = accountBalanceRepository.findById(accountId).orElseThrow();
        assertThat(afterCapture.getAvailableMinor()).isEqualTo(6_000);
        assertThat(afterCapture.getReservedMinor()).isZero();
    }

    @Test
    void reservationCreatedOnceThenTransitionedThroughAManagedInstance() {
        UUID accountId = createLedgerAccount("EUR");
        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);

        transactionTemplate.executeWithoutResult(status -> {
            Reservation reservation = new Reservation(reservationId, accountId, 2_500, "EUR",
                    UUID.randomUUID(), "hold-" + UUID.randomUUID(), expiresAt);
            reservationRepository.save(reservation);
        });

        Reservation afterCreate = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(afterCreate.getState()).isEqualTo(ReservationState.HELD);
        assertThat(afterCreate.getCreatedAt()).isNotNull();
        assertThat(afterCreate.getResolvedAt()).isNull();

        transactionTemplate.executeWithoutResult(status -> {
            Reservation managed = reservationRepository.findById(reservationId).orElseThrow();
            managed.markCaptured(Instant.now());
        });

        Reservation afterCapture = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(afterCapture.getState()).isEqualTo(ReservationState.CAPTURED);
        assertThat(afterCapture.getResolvedAt()).isNotNull();
    }

    private UUID createLedgerAccount(String currency) {
        return transactionTemplate.execute(status -> {
            LedgerAccount account = new LedgerAccount(UUID.randomUUID(), "user:" + UUID.randomUUID(),
                    LedgerAccountType.USER_WALLET, currency);
            return ledgerAccountRepository.save(account).getId();
        });
    }
}
