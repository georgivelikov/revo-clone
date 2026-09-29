package com.revolutclone.analytics.budget;

import static com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures.approved;
import static com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures.settled;
import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.analytics.categorisation.Category;
import com.revolutclone.analytics.consumer.AuthorizationApprovedEventHandler;
import com.revolutclone.analytics.consumer.AuthorizationSettledEventHandler;
import com.revolutclone.analytics.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.messaging.outbox.OutboxEvent;
import com.revolutclone.messaging.outbox.OutboxEventRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
 * plans/phase-06-vaults-analytics.md's named test for task 06.5: "Crossing 80% then 100% fires
 * once each, never twice." A single budget with a 10,000-minor-unit (EUR 100.00) limit is crossed
 * by three separate settlements: the first clears 80% only, the second clears 100% too, and the
 * third leaves the total further over 100% still - proving the third produces no new notification.
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
class BudgetAlertTest {

    private static final String MERCHANT = "Tesco Express";
    private static final String GROCERIES_MCC = "5411"; // seeded by V5__categorisation.sql -> Groceries
    private static final String NOTIFICATION_TOPIC = "notification.push.v1";

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
    private OutboxEventRepository outbox;

    @BeforeEach
    void resetState() {
        // Every test method here shares one Spring context and one Postgres container - clear the
        // tables each test asserts on globally (outbox.findAll(), not scoped by userId) so one
        // method's rows cannot leak into the next.
        outbox.deleteAll();
        budgets.deleteAll();
    }

    @Test
    void crossingEightyThenAHundredPercentFiresEachAlertExactlyOnceAndNeverAgain() {
        UUID userId = UUID.randomUUID();
        LocalDate month = LocalDate.of(2024, 6, 1);
        long limitMinor = 10_000L; // EUR 100.00

        Budget budget = budgetService.upsert(userId, Category.GROCERIES, month, limitMinor, "EUR");

        // First settlement: 8,500 / 10,000 = 85% - crosses 80%, not yet 100%.
        Instant firstOccurredAt = Instant.parse("2024-06-05T09:00:00Z");
        settle(userId, 8_500L, firstOccurredAt);

        List<OutboxEvent> afterFirst = outbox.findAll();
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.getFirst().getTopic()).isEqualTo(NOTIFICATION_TOPIC);
        // CLAUDE.md hard rule 5: notification.push.v1 is keyed by userId.
        assertThat(afterFirst.getFirst().getPartitionKey()).isEqualTo(userId.toString());

        Budget afterFirstBudget = budgets.findById(budget.getId()).orElseThrow();
        assertThat(afterFirstBudget.getAlertedAt80()).isEqualTo(firstOccurredAt);
        assertThat(afterFirstBudget.getAlertedAt100()).isNull();

        // Second settlement: total now 10,500 / 10,000 = 105% - crosses 100% too.
        Instant secondOccurredAt = Instant.parse("2024-06-10T09:00:00Z");
        settle(userId, 2_000L, secondOccurredAt);

        List<OutboxEvent> afterSecond = outbox.findAll();
        assertThat(afterSecond).hasSize(2);

        Budget afterSecondBudget = budgets.findById(budget.getId()).orElseThrow();
        // The 80% guard is untouched by the second crossing - it still records the first event.
        assertThat(afterSecondBudget.getAlertedAt80()).isEqualTo(firstOccurredAt);
        assertThat(afterSecondBudget.getAlertedAt100()).isEqualTo(secondOccurredAt);

        // Third settlement: total climbs further past both thresholds - neither may fire again.
        Instant thirdOccurredAt = Instant.parse("2024-06-15T09:00:00Z");
        settle(userId, 1_000L, thirdOccurredAt);

        assertThat(outbox.findAll()).hasSize(2);
        Budget afterThirdBudget = budgets.findById(budget.getId()).orElseThrow();
        assertThat(afterThirdBudget.getAlertedAt80()).isEqualTo(firstOccurredAt);
        assertThat(afterThirdBudget.getAlertedAt100()).isEqualTo(secondOccurredAt);
    }

    @Test
    void aSettlementThatClearsBothThresholdsAtOnceFiresBothExactlyOnce() {
        UUID userId = UUID.randomUUID();
        LocalDate month = LocalDate.of(2024, 7, 1);
        long limitMinor = 10_000L;
        Budget budget = budgetService.upsert(userId, Category.GROCERIES, month, limitMinor, "EUR");

        // A single settlement that jumps straight past both 80% and 100% in one go.
        Instant occurredAt = Instant.parse("2024-07-03T09:00:00Z");
        settle(userId, 15_000L, occurredAt);

        assertThat(outbox.findAll()).hasSize(2);
        Budget afterBudget = budgets.findById(budget.getId()).orElseThrow();
        assertThat(afterBudget.getAlertedAt80()).isEqualTo(occurredAt);
        assertThat(afterBudget.getAlertedAt100()).isEqualTo(occurredAt);
    }

    @Test
    void noBudgetSetMeansNoAlertEverFires() {
        UUID userId = UUID.randomUUID();
        // No budget created for this user/category/month/currency at all.
        settle(userId, 50_000L, Instant.parse("2024-08-01T09:00:00Z"));

        assertThat(outbox.findAll()).isEmpty();
    }

    private void settle(UUID userId, long settledMinor, Instant occurredAt) {
        UUID authorizationId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        approvedHandler.handle(approved(authorizationId, cardId, userId, accountId, MERCHANT, GROCERIES_MCC,
                settledMinor, "EUR", occurredAt));
        settledHandler.handle(settled(authorizationId, cardId, userId, accountId, MERCHANT, GROCERIES_MCC,
                settledMinor, "EUR", occurredAt));
    }
}
