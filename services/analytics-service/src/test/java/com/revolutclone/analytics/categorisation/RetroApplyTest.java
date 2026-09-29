package com.revolutclone.analytics.categorisation;

import static com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures.approved;
import static com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures.settled;
import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.analytics.consumer.AuthorizationApprovedEventHandler;
import com.revolutclone.analytics.consumer.AuthorizationSettledEventHandler;
import com.revolutclone.analytics.spend.MonthlySpend;
import com.revolutclone.analytics.spend.MonthlySpendRepository;
import com.revolutclone.analytics.testsupport.NoOpJwtDecoderTestConfig;
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
 * plans/phase-06-vaults-analytics.md's named test for task 06.4: "Override updates past
 * transactions and corrects aggregates exactly." Exercises the whole path -
 * {@link MerchantCategoryOverrideService#setOverride} triggering {@link RetroApplyService} - since
 * that is exactly how a real override is set (see that service's own javadoc for why setting the
 * {@link MerchantCategoryOverrideRepository} row directly, as {@code CategorisationPriorityTest}
 * does, does <em>not</em> retro-apply).
 */
@Testcontainers
@SpringBootTest
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class RetroApplyTest {

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
    private MerchantCategoryOverrideService overrideService;

    @Autowired
    private TransactionCategoryRepository transactionCategories;

    @Autowired
    private MonthlySpendRepository monthlySpend;

    @Test
    void overrideRetroAppliesToPastSettledTransactionAndCorrectsAggregatesExactly() {
        UUID userId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2024-06-10T09:00:00Z");
        LocalDate month = LocalDate.of(2024, 6, 1);

        UUID authorizationId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        approvedHandler.handle(approved(authorizationId, cardId, userId, accountId, MERCHANT, GROCERIES_MCC, 4_370L,
                "EUR", occurredAt));
        settledHandler.handle(settled(authorizationId, cardId, userId, accountId, MERCHANT, GROCERIES_MCC, 4_370L,
                "EUR", occurredAt));

        // Before the override: MCC-derived Groceries, both in the category row and the aggregate.
        assertThat(transactionCategories.findById(authorizationId).orElseThrow().getCategory())
                .isEqualTo(Category.GROCERIES);
        assertThat(monthlySpend.findById(new MonthlySpend.Key(userId, month, Category.GROCERIES, "EUR"))
                .orElseThrow().getTotalMinor()).isEqualTo(4_370L);

        overrideService.setOverride(userId, MERCHANT, Category.SHOPPING);

        // The past transaction's category is corrected, and its source becomes USER.
        TransactionCategory corrected = transactionCategories.findById(authorizationId).orElseThrow();
        assertThat(corrected.getCategory()).isEqualTo(Category.SHOPPING);
        assertThat(corrected.getSource()).isEqualTo(CategorisationSource.USER);

        // The old bucket loses exactly this settlement's contribution...
        MonthlySpend oldBucket = monthlySpend
                .findById(new MonthlySpend.Key(userId, month, Category.GROCERIES, "EUR")).orElseThrow();
        assertThat(oldBucket.getTotalMinor()).isEqualTo(0L);
        assertThat(oldBucket.getTxnCount()).isEqualTo(0);

        // ...and the new bucket gains exactly that same amount, not a re-derived or rounded one.
        MonthlySpend newBucket = monthlySpend
                .findById(new MonthlySpend.Key(userId, month, Category.SHOPPING, "EUR")).orElseThrow();
        assertThat(newBucket.getTotalMinor()).isEqualTo(4_370L);
        assertThat(newBucket.getTxnCount()).isEqualTo(1);
    }

    @Test
    void settingTheSameOverrideValueTwiceIsASafeNoOp() {
        UUID userId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2024-07-05T12:00:00Z");
        LocalDate month = LocalDate.of(2024, 7, 1);

        UUID authorizationId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        approvedHandler.handle(approved(authorizationId, cardId, userId, accountId, MERCHANT, GROCERIES_MCC, 1_200L,
                "EUR", occurredAt));
        settledHandler.handle(settled(authorizationId, cardId, userId, accountId, MERCHANT, GROCERIES_MCC, 1_200L,
                "EUR", occurredAt));

        overrideService.setOverride(userId, MERCHANT, Category.SHOPPING);
        // Second call, same value: must not double-move the aggregate or touch the category again.
        overrideService.setOverride(userId, MERCHANT, Category.SHOPPING);

        assertThat(transactionCategories.findById(authorizationId).orElseThrow().getCategory())
                .isEqualTo(Category.SHOPPING);

        MonthlySpend shoppingBucket = monthlySpend
                .findById(new MonthlySpend.Key(userId, month, Category.SHOPPING, "EUR")).orElseThrow();
        assertThat(shoppingBucket.getTotalMinor()).isEqualTo(1_200L);
        assertThat(shoppingBucket.getTxnCount()).isEqualTo(1);

        MonthlySpend groceriesBucket = monthlySpend
                .findById(new MonthlySpend.Key(userId, month, Category.GROCERIES, "EUR")).orElseThrow();
        assertThat(groceriesBucket.getTotalMinor()).isEqualTo(0L);
        assertThat(groceriesBucket.getTxnCount()).isEqualTo(0);
    }

    @Test
    void anUnsettledAuthorizationIsRecategorisedButNeedsNoAggregateCorrection() {
        UUID userId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2024-08-01T08:00:00Z");

        UUID authorizationId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        // Approved only - never settled, so it never contributed to monthly_spend at all.
        approvedHandler.handle(approved(authorizationId, cardId, userId, accountId, MERCHANT, GROCERIES_MCC, 900L,
                "EUR", occurredAt));

        assertThat(transactionCategories.findById(authorizationId).orElseThrow().getCategory())
                .isEqualTo(Category.GROCERIES);

        overrideService.setOverride(userId, MERCHANT, Category.SHOPPING);

        // The category row is still corrected...
        TransactionCategory corrected = transactionCategories.findById(authorizationId).orElseThrow();
        assertThat(corrected.getCategory()).isEqualTo(Category.SHOPPING);
        assertThat(corrected.getSource()).isEqualTo(CategorisationSource.USER);

        // ...but there was never a bucket to correct in either direction.
        LocalDate month = LocalDate.of(2024, 8, 1);
        assertThat(monthlySpend.findById(new MonthlySpend.Key(userId, month, Category.GROCERIES, "EUR")))
                .isEmpty();
        assertThat(monthlySpend.findById(new MonthlySpend.Key(userId, month, Category.SHOPPING, "EUR")))
                .isEmpty();
    }

    @Test
    void anAuthorizationAlreadyAtTheOverrideCategoryIsSkippedEntirely() {
        UUID userId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2024-09-12T10:00:00Z");
        LocalDate month = LocalDate.of(2024, 9, 1);

        UUID authorizationId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        approvedHandler.handle(approved(authorizationId, cardId, userId, accountId, MERCHANT, GROCERIES_MCC, 3_000L,
                "EUR", occurredAt));
        settledHandler.handle(settled(authorizationId, cardId, userId, accountId, MERCHANT, GROCERIES_MCC, 3_000L,
                "EUR", occurredAt));

        Instant assignedAtBefore = transactionCategories.findById(authorizationId).orElseThrow().getAssignedAt();

        // Overriding to the category it already has (Groceries via MCC) - point 4 of the task
        // spec: skip entirely, do not touch the aggregate or bump anything.
        overrideService.setOverride(userId, MERCHANT, Category.GROCERIES);

        TransactionCategory unchanged = transactionCategories.findById(authorizationId).orElseThrow();
        assertThat(unchanged.getCategory()).isEqualTo(Category.GROCERIES);
        assertThat(unchanged.getAssignedAt()).isEqualTo(assignedAtBefore);

        MonthlySpend bucket = monthlySpend
                .findById(new MonthlySpend.Key(userId, month, Category.GROCERIES, "EUR")).orElseThrow();
        assertThat(bucket.getTotalMinor()).isEqualTo(3_000L);
        assertThat(bucket.getTxnCount()).isEqualTo(1);
    }
}
