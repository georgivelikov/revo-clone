package com.revolutclone.analytics.spend;

import static com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures.approved;
import static com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures.settled;
import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.analytics.categorisation.Category;
import com.revolutclone.analytics.categorisation.TransactionCategoryRepository;
import com.revolutclone.analytics.consumer.AuthorizationApprovedEventHandler;
import com.revolutclone.analytics.consumer.AuthorizationSettledEventHandler;
import com.revolutclone.analytics.readmodel.CardAuthorizationFactRepository;
import com.revolutclone.analytics.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationSettledV1;
import com.revolutclone.messaging.idempotent.ProcessedEventRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-06-vaults-analytics.md's own real test for this phase: "Drop DB, replay from offset
 * 0 -> identical aggregates." {@code monthly_spend} is rebuilt here by wiping every table this
 * task's write path touches - the aggregate itself, the categorisation it reads, the fact row the
 * settlement handler also writes, and {@code processed_event} (a genuine DB drop takes the dedup
 * guard with it too) - and then redelivering byte-identical envelopes (same {@code eventId}s, same
 * {@code occurredAt}s, the way a real Kafka replay would). The rebuilt aggregate must match the
 * original exactly, because the whole point of a projection is that it is a pure function of the
 * event stream with no hidden state.
 *
 * <p>{@link #redeliveringTheSameSettlementWithoutDroppingTheDatabaseDoesNotDoubleCount()} is the
 * companion case the phase's "Risks & gotchas" section calls out by name: an incremental sum,
 * unlike an idempotent balance update, drifts on a redelivered event unless something stops it -
 * here, the {@code IdempotentHandler} guard around {@code MonthlySpendProjectionHandler}.
 */
@Testcontainers
@SpringBootTest
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class ProjectionRebuildTest {

    private static final String MERCHANT = "Tesco Express";
    private static final String MCC = "5411";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private AuthorizationApprovedEventHandler approvedHandler;

    @Autowired
    private AuthorizationSettledEventHandler settledHandler;

    @Autowired
    private MonthlySpendRepository monthlySpend;

    @Autowired
    private TransactionCategoryRepository transactionCategories;

    @Autowired
    private CardAuthorizationFactRepository cardAuthorizationFacts;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Test
    void droppingTheProjectionAndReplayingTheSameEventsProducesIdenticalAggregates() {
        UUID userId = UUID.randomUUID();
        LocalDate month = LocalDate.of(2024, 2, 1);

        UUID auth1 = UUID.randomUUID();
        UUID card1 = UUID.randomUUID();
        UUID account1 = UUID.randomUUID();
        Instant occurredAt1 = Instant.parse("2024-02-05T12:00:00Z");
        UUID settleEvent1 = UUID.randomUUID();

        UUID auth2 = UUID.randomUUID();
        UUID card2 = UUID.randomUUID();
        UUID account2 = UUID.randomUUID();
        Instant occurredAt2 = Instant.parse("2024-02-20T18:30:00Z");
        UUID settleEvent2 = UUID.randomUUID();

        replayBoth(userId, auth1, card1, account1, occurredAt1, settleEvent1, auth2, card2, account2, occurredAt2,
                settleEvent2);

        MonthlySpend before = monthlySpend.findById(new MonthlySpend.Key(userId, month, Category.GROCERIES, "EUR"))
                .orElseThrow();
        assertThat(before.getTotalMinor()).isEqualTo(3_500L);
        assertThat(before.getTxnCount()).isEqualTo(2);

        // "Drop its entire database" - every table this task's write path touches, including the
        // processed_event guard, which a real DB drop takes with it too.
        //
        // processed_event specifically needs deleteAllInBatch(), not deleteAll(): ProcessedEvent
        // always reports isNew() == true (by design - see its own javadoc, needed to force save()
        // through persist() rather than merge()), and Spring Data JPA's plain deleteAll() calls
        // delete(T) per entity, which silently skips any entity for which isNew() is true. A bulk
        // deleteAllInBatch() issues one JPQL DELETE and never consults isNew() at all.
        monthlySpend.deleteAll();
        transactionCategories.deleteAll();
        cardAuthorizationFacts.deleteAll();
        processedEvents.deleteAllInBatch();

        // "Replay the topics from offset 0" - the same envelopes, unchanged, redelivered.
        replayBoth(userId, auth1, card1, account1, occurredAt1, settleEvent1, auth2, card2, account2, occurredAt2,
                settleEvent2);

        MonthlySpend after = monthlySpend.findById(new MonthlySpend.Key(userId, month, Category.GROCERIES, "EUR"))
                .orElseThrow();
        assertThat(after.getTotalMinor()).isEqualTo(before.getTotalMinor());
        assertThat(after.getTxnCount()).isEqualTo(before.getTxnCount());
    }

    @Test
    void redeliveringTheSameSettlementWithoutDroppingTheDatabaseDoesNotDoubleCount() {
        UUID userId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2024-05-01T09:00:00Z");
        LocalDate month = LocalDate.of(2024, 5, 1);

        approvedHandler.handle(approved(authorizationId, cardId, userId, accountId, MERCHANT, MCC, 4_370L, "EUR",
                occurredAt));
        EventEnvelope<AuthorizationSettledV1> settlement = settled(UUID.randomUUID(), authorizationId, cardId,
                userId, accountId, MERCHANT, MCC, 4_370L, "EUR", occurredAt);

        settledHandler.handle(settlement);
        settledHandler.handle(settlement); // a genuine redelivery: the very same envelope, unchanged
        settledHandler.handle(settlement);

        MonthlySpend row = monthlySpend.findById(new MonthlySpend.Key(userId, month, Category.GROCERIES, "EUR"))
                .orElseThrow();
        assertThat(row.getTotalMinor()).isEqualTo(4_370L);
        assertThat(row.getTxnCount()).isEqualTo(1);
    }

    private void replayBoth(UUID userId, UUID auth1, UUID card1, UUID account1, Instant occurredAt1,
            UUID settleEvent1, UUID auth2, UUID card2, UUID account2, Instant occurredAt2, UUID settleEvent2) {
        approvedHandler.handle(approved(auth1, card1, userId, account1, MERCHANT, MCC, 1_000L, "EUR", occurredAt1));
        settledHandler.handle(settled(settleEvent1, auth1, card1, userId, account1, MERCHANT, MCC, 1_000L, "EUR",
                occurredAt1));
        approvedHandler.handle(approved(auth2, card2, userId, account2, MERCHANT, MCC, 2_500L, "EUR", occurredAt2));
        settledHandler.handle(settled(settleEvent2, auth2, card2, userId, account2, MERCHANT, MCC, 2_500L, "EUR",
                occurredAt2));
    }
}
