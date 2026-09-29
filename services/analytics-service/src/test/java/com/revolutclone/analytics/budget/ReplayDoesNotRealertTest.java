package com.revolutclone.analytics.budget;

import static com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures.approved;
import static com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures.settled;
import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.analytics.categorisation.Category;
import com.revolutclone.analytics.consumer.AuthorizationApprovedEventHandler;
import com.revolutclone.analytics.consumer.AuthorizationSettledEventHandler;
import com.revolutclone.analytics.spend.MonthlySpend;
import com.revolutclone.analytics.spend.MonthlySpendRepository;
import com.revolutclone.analytics.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationApprovedV1;
import com.revolutclone.contracts.events.AuthorizationSettledV1;
import com.revolutclone.messaging.idempotent.ProcessedEventRepository;
import com.revolutclone.messaging.outbox.OutboxEventRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * plans/phase-06-vaults-analytics.md's named test for task 06.5: "Full topic replay reproduces
 * aggregates and sends zero notifications." This is deliberately not just a redelivery test of a
 * single duplicate message - it models the phase's actual replay story (see
 * {@code ProjectionRebuildTest}, task 06.1's equivalent): {@code monthly_spend} and
 * {@code processed_event} are projections and get dropped and rebuilt, but {@code budget} is
 * user-configured data and is not - so after the rebuild, {@code monthly_spend} must come back
 * byte-identical, while the {@code alerted_at_80}/{@code alerted_at_100} columns that survived the
 * drop are what stop the replay from re-notifying, not whatever {@code processed_event} state
 * happens to exist afterwards.
 *
 * <p>The two settlements are replayed with their original {@code eventId}s -
 * {@link com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures}'s own javadoc
 * calls this "what a genuine topic replay redelivers verbatim" - even though {@code
 * processed_event} has been wiped and therefore cannot itself dedup them; the budget's own guard
 * columns have to do the whole job here.
 */
@Testcontainers
@SpringBootTest
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        // Keeps the background OutboxPublisher from racing this test's own outbox.findAll()
        // assertions against a real (or absent) broker at the fake bootstrap-servers address
        // above - the same reason CardIssuanceTest sets this for the identical assertion shape.
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class ReplayDoesNotRealertTest {

    private static final String MERCHANT = "Tesco Express";
    private static final String GROCERIES_MCC = "5411"; // seeded by V5__categorisation.sql -> Groceries

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private AuthorizationApprovedEventHandler approvedHandler;

    @Autowired
    private AuthorizationSettledEventHandler settledHandler;

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private BudgetRepository budgets;

    @Autowired
    private MonthlySpendRepository monthlySpend;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void resetState() {
        // Every test method here shares one Spring context and one Postgres container - clear the
        // tables each test asserts on globally (outbox.findAll(), not scoped by userId) so one
        // method's rows cannot leak into the next.
        outbox.deleteAll();
        budgets.deleteAll();
        monthlySpend.deleteAll();
        // ProcessedEvent.isNew() always reports true (see that class's own javadoc - it forces
        // save() through persist() so a duplicate throws instead of silently upserting), but
        // Spring Data's plain delete()/deleteAll() also consult isNew() and silently skip any
        // entity that reports true - so deleteAll() here would be a no-op. deleteAllInBatch()
        // issues a bulk JPQL DELETE instead, unaffected by isNew(), which is what an actual
        // dropped-and-recreated processed_event table needs to model.
        processedEvents.deleteAllInBatch();
    }

    @Test
    void aFullProjectionRebuildReproducesTheSameAggregatesAndSendsNoAdditionalNotifications() {
        UUID userId = UUID.randomUUID();
        LocalDate month = LocalDate.of(2024, 9, 1);
        long limitMinor = 10_000L; // EUR 100.00
        budgetService.upsert(userId, Category.GROCERIES, month, limitMinor, "EUR");

        UUID firstAuthorization = UUID.randomUUID();
        UUID firstCard = UUID.randomUUID();
        UUID firstAccount = UUID.randomUUID();
        UUID firstEventId = UUID.randomUUID();
        Instant firstOccurredAt = Instant.parse("2024-09-05T09:00:00Z");

        UUID secondAuthorization = UUID.randomUUID();
        UUID secondCard = UUID.randomUUID();
        UUID secondAccount = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();
        Instant secondOccurredAt = Instant.parse("2024-09-10T09:00:00Z");

        // First pass: settle both, crossing 80% then 100%.
        approvedHandler.handle(approved(firstAuthorization, firstCard, userId, firstAccount, MERCHANT,
                GROCERIES_MCC, 8_500L, "EUR", firstOccurredAt));
        settledHandler.handle(
                settled(firstEventId, firstAuthorization, firstCard, userId, firstAccount, MERCHANT, GROCERIES_MCC,
                        8_500L, "EUR", firstOccurredAt));

        approvedHandler.handle(approved(secondAuthorization, secondCard, userId, secondAccount, MERCHANT,
                GROCERIES_MCC, 2_000L, "EUR", secondOccurredAt));
        settledHandler.handle(
                settled(secondEventId, secondAuthorization, secondCard, userId, secondAccount, MERCHANT,
                        GROCERIES_MCC, 2_000L, "EUR", secondOccurredAt));

        MonthlySpend beforeRebuild = monthlySpend
                .findById(new MonthlySpend.Key(userId, month, Category.GROCERIES, "EUR")).orElseThrow();
        assertThat(beforeRebuild.getTotalMinor()).isEqualTo(10_500L);
        assertThat(beforeRebuild.getTxnCount()).isEqualTo(2);
        assertThat(outbox.findAll()).hasSize(2);

        Budget beforeRebuildBudget = budgets.findByUserIdAndCategoryAndMonthAndCurrency(userId, Category.GROCERIES,
                month, "EUR").orElseThrow();
        assertThat(beforeRebuildBudget.getAlertedAt80()).isEqualTo(firstOccurredAt);
        assertThat(beforeRebuildBudget.getAlertedAt100()).isEqualTo(secondOccurredAt);

        // Simulate the phase's actual rebuild: drop the projections (monthly_spend,
        // processed_event, and the categorisation this test doesn't otherwise need to touch) but
        // leave the user-configured budget row exactly as it is - the phase's own "the budget row
        // itself is user-configured data... not expected to be dropped by a rebuild" note.
        monthlySpend.deleteAll();
        // ProcessedEvent.isNew() always reports true (see that class's own javadoc - it forces
        // save() through persist() so a duplicate throws instead of silently upserting), but
        // Spring Data's plain delete()/deleteAll() also consult isNew() and silently skip any
        // entity that reports true - so deleteAll() here would be a no-op. deleteAllInBatch()
        // issues a bulk JPQL DELETE instead, unaffected by isNew(), which is what an actual
        // dropped-and-recreated processed_event table needs to model.
        processedEvents.deleteAllInBatch();
        // The categorisation rows are also projections in principle, but this test only needs the
        // settlement path to re-run identically; re-approving would just re-derive the same
        // Groceries category from the same MCC, so leaving transaction_category alone changes
        // nothing being asserted here and keeps the test focused on the budget guard.

        // Replay from offset zero: both settlements redelivered verbatim, same eventIds.
        settledHandler.handle(
                settled(firstEventId, firstAuthorization, firstCard, userId, firstAccount, MERCHANT, GROCERIES_MCC,
                        8_500L, "EUR", firstOccurredAt));
        settledHandler.handle(
                settled(secondEventId, secondAuthorization, secondCard, userId, secondAccount, MERCHANT,
                        GROCERIES_MCC, 2_000L, "EUR", secondOccurredAt));

        // The projection comes back byte-identical...
        MonthlySpend afterRebuild = monthlySpend
                .findById(new MonthlySpend.Key(userId, month, Category.GROCERIES, "EUR")).orElseThrow();
        assertThat(afterRebuild.getTotalMinor()).isEqualTo(10_500L);
        assertThat(afterRebuild.getTxnCount()).isEqualTo(2);

        // ...but not one additional notification was sent, even though processed_event (which
        // would ordinarily have deduped this) was wiped along with monthly_spend: the budget's own
        // alerted_at_80/alerted_at_100 columns - never dropped - are what actually stopped it.
        assertThat(outbox.findAll()).hasSize(2);
        Budget afterRebuildBudget = budgets.findByUserIdAndCategoryAndMonthAndCurrency(userId, Category.GROCERIES,
                month, "EUR").orElseThrow();
        assertThat(afterRebuildBudget.getAlertedAt80()).isEqualTo(firstOccurredAt);
        assertThat(afterRebuildBudget.getAlertedAt100()).isEqualTo(secondOccurredAt);
    }

    @Test
    void redeliveryOfTheSameSettlementEventIsDedupedBeforeEverReachingTheBudgetGuard() {
        UUID userId = UUID.randomUUID();
        LocalDate month = LocalDate.of(2024, 10, 1);
        budgetService.upsert(userId, Category.GROCERIES, month, 10_000L, "EUR");

        UUID authorizationId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2024-10-05T09:00:00Z");

        EventEnvelope<AuthorizationApprovedV1> approvedEnvelope = approved(authorizationId, cardId, userId,
                accountId, MERCHANT, GROCERIES_MCC, 9_000L, "EUR", occurredAt);
        EventEnvelope<AuthorizationSettledV1> settledEnvelope = settled(eventId, authorizationId, cardId, userId,
                accountId, MERCHANT, GROCERIES_MCC, 9_000L, "EUR", occurredAt);

        approvedHandler.handle(approvedEnvelope);
        settledHandler.handle(settledEnvelope);
        // A genuine redelivery: same eventId, nothing dropped in between. The outer
        // processed_event guard on the monthly_spend increment already stops this from running
        // a second time at all.
        settledHandler.handle(settledEnvelope);

        MonthlySpend bucket = monthlySpend.findById(new MonthlySpend.Key(userId, month, Category.GROCERIES, "EUR"))
                .orElseThrow();
        assertThat(bucket.getTotalMinor()).isEqualTo(9_000L);
        assertThat(bucket.getTxnCount()).isEqualTo(1);
        assertThat(outbox.findAll()).hasSize(1);
    }
}
