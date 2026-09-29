package com.revolutclone.analytics.query;

import static com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures.approved;
import static com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures.settled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revolutclone.analytics.categorisation.Category;
import com.revolutclone.analytics.categorisation.InvalidCategoryException;
import com.revolutclone.analytics.consumer.AuthorizationApprovedEventHandler;
import com.revolutclone.analytics.consumer.AuthorizationSettledEventHandler;
import com.revolutclone.analytics.testsupport.NoOpJwtDecoderTestConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
 * Task 06.8's four read endpoints, exercised at the service layer through the same real
 * {@code card.authorization.v1} handlers tasks 06.1-06.3 already ship (see
 * {@code MultiCurrencySpendTest} for the same pattern) so that {@code monthly_spend},
 * {@code transaction_category} and {@code card_authorization_fact} all end up in the state a real
 * settlement would produce, rather than being hand-built to fit the query.
 */
@Testcontainers
@SpringBootTest
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class AnalyticsQueryServiceTest {

    private static final String GROCERIES_MCC = "5411";
    private static final String RESTAURANTS_MCC = "5812";
    private static final LocalDate MONTH = LocalDate.of(2024, 6, 1);
    private static final Instant OCCURRED_AT = Instant.parse("2024-06-10T09:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private AuthorizationApprovedEventHandler approvedHandler;

    @Autowired
    private AuthorizationSettledEventHandler settledHandler;

    @Autowired
    private AnalyticsQueryService queryService;

    private void settle(UUID userId, String merchant, String mcc, long amountMinor, String currency,
            Instant occurredAt) {
        UUID authorizationId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        approvedHandler.handle(approved(authorizationId, cardId, userId, accountId, merchant, mcc, amountMinor,
                currency, occurredAt));
        settledHandler.handle(settled(authorizationId, cardId, userId, accountId, merchant, mcc, amountMinor,
                currency, occurredAt));
    }

    @Test
    void monthlySummaryBreaksDownByCategoryAndCurrencyWithoutSumming() {
        UUID userId = UUID.randomUUID();
        settle(userId, "Tesco", GROCERIES_MCC, 4_370L, "EUR", OCCURRED_AT);
        settle(userId, "Tesco", GROCERIES_MCC, 2_500L, "GBP", OCCURRED_AT);
        settle(userId, "Nandos", RESTAURANTS_MCC, 1_200L, "EUR", OCCURRED_AT);

        MonthlySummary summary = queryService.monthlySummary(userId, MONTH);

        assertThat(summary.month()).isEqualTo(MONTH);
        assertThat(summary.categories()).containsExactlyInAnyOrder(
                new CategorySpend(Category.GROCERIES, "EUR", 4_370L, 1),
                new CategorySpend(Category.GROCERIES, "GBP", 2_500L, 1),
                new CategorySpend(Category.RESTAURANTS, "EUR", 1_200L, 1));
    }

    @Test
    void monthlySummaryIsEmptyRatherThanNotFoundForAMonthWithNoActivity() {
        UUID userId = UUID.randomUUID();
        settle(userId, "Tesco", GROCERIES_MCC, 4_370L, "EUR", OCCURRED_AT);

        MonthlySummary summary = queryService.monthlySummary(userId, LocalDate.of(2023, 1, 1));

        assertThat(summary.categories()).isEmpty();
    }

    @Test
    void monthlySummaryNeverLeaksAnotherUsersSpend() {
        UUID userId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        settle(userId, "Tesco", GROCERIES_MCC, 4_370L, "EUR", OCCURRED_AT);
        settle(otherUser, "Tesco", GROCERIES_MCC, 9_999L, "EUR", OCCURRED_AT);

        MonthlySummary summary = queryService.monthlySummary(userId, MONTH);

        assertThat(summary.categories()).containsExactly(new CategorySpend(Category.GROCERIES, "EUR", 4_370L, 1));
    }

    @Test
    void categoryDrillDownListsOnlySettledTransactionsForThatCategoryAndMonth() {
        UUID userId = UUID.randomUUID();
        settle(userId, "Tesco", GROCERIES_MCC, 4_370L, "EUR", OCCURRED_AT);
        settle(userId, "Aldi", GROCERIES_MCC, 1_000L, "EUR", OCCURRED_AT);
        // Different category - must not appear in the Groceries drill-down.
        settle(userId, "Nandos", RESTAURANTS_MCC, 1_200L, "EUR", OCCURRED_AT);
        // Same category, different (earlier) month - must not appear either.
        settle(userId, "Tesco", GROCERIES_MCC, 500L, "EUR", Instant.parse("2024-05-10T09:00:00Z"));

        List<DrillDownTransaction> transactions = queryService.categoryDrillDown(userId, Category.GROCERIES, MONTH);

        assertThat(transactions).hasSize(2);
        assertThat(transactions).extracting(DrillDownTransaction::merchantName)
                .containsExactlyInAnyOrder("Tesco", "Aldi");
        assertThat(transactions).allMatch(t -> t.currency().equals("EUR"));
        assertThat(transactions).extracting(DrillDownTransaction::amountMinor).containsExactlyInAnyOrder(4_370L,
                1_000L);
    }

    @Test
    void categoryDrillDownIsEmptyForACategoryTheUserNeverSpentIn() {
        UUID userId = UUID.randomUUID();
        settle(userId, "Tesco", GROCERIES_MCC, 4_370L, "EUR", OCCURRED_AT);

        List<DrillDownTransaction> transactions = queryService.categoryDrillDown(userId, Category.TRAVEL, MONTH);

        assertThat(transactions).isEmpty();
    }

    @Test
    void categoryDrillDownRejectsACategoryOutsideTheFixedSet() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> queryService.categoryDrillDown(userId, "NotACategory", MONTH))
                .isInstanceOf(InvalidCategoryException.class);
    }

    @Test
    void categoryDrillDownNeverLeaksAnotherUsersTransactions() {
        UUID userId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        settle(userId, "Tesco", GROCERIES_MCC, 4_370L, "EUR", OCCURRED_AT);
        settle(otherUser, "Tesco", GROCERIES_MCC, 9_999L, "EUR", OCCURRED_AT);

        List<DrillDownTransaction> transactions = queryService.categoryDrillDown(userId, Category.GROCERIES, MONTH);

        assertThat(transactions).extracting(DrillDownTransaction::amountMinor).containsExactly(4_370L);
    }

    @Test
    void trendSumsAcrossCategoriesButNeverAcrossCurrenciesWithinAMonth() {
        UUID userId = UUID.randomUUID();
        settle(userId, "Tesco", GROCERIES_MCC, 4_370L, "EUR", OCCURRED_AT);
        settle(userId, "Nandos", RESTAURANTS_MCC, 1_200L, "EUR", OCCURRED_AT);
        settle(userId, "Tesco", GROCERIES_MCC, 2_500L, "GBP", OCCURRED_AT);

        List<TrendPoint> trend = queryService.trend(userId, MONTH, 6);

        assertThat(trend).containsExactlyInAnyOrder(new TrendPoint(MONTH, "EUR", 5_570L, 2),
                new TrendPoint(MONTH, "GBP", 2_500L, 1));
    }

    @Test
    void trendOnlyIncludesTheRequestedNumberOfMonthsUpToAsOf() {
        UUID userId = UUID.randomUUID();
        settle(userId, "Tesco", GROCERIES_MCC, 1_000L, "EUR", OCCURRED_AT); // June 2024
        settle(userId, "Tesco", GROCERIES_MCC, 2_000L, "EUR", Instant.parse("2023-12-15T09:00:00Z")); // outside a
                                                                                                        // 6-month
                                                                                                        // window
                                                                                                        // ending June

        List<TrendPoint> trend = queryService.trend(userId, MONTH, 6);

        assertThat(trend).containsExactly(new TrendPoint(MONTH, "EUR", 1_000L, 1));
    }

    @Test
    void topMerchantsRanksByTotalSettledSpendDescendingAndKeepsCurrenciesSeparate() {
        UUID userId = UUID.randomUUID();
        settle(userId, "Tesco", GROCERIES_MCC, 1_000L, "EUR", OCCURRED_AT);
        settle(userId, "Tesco", GROCERIES_MCC, 500L, "EUR", OCCURRED_AT);
        settle(userId, "Nandos", RESTAURANTS_MCC, 4_000L, "EUR", OCCURRED_AT);
        settle(userId, "Tesco", GROCERIES_MCC, 9_000L, "GBP", OCCURRED_AT);

        List<TopMerchant> top = queryService.topMerchants(userId, MONTH, 10);

        assertThat(top).containsExactly(
                new TopMerchant("Tesco", "GBP", 9_000L, 1),
                new TopMerchant("Nandos", "EUR", 4_000L, 1),
                new TopMerchant("Tesco", "EUR", 1_500L, 2));
    }

    @Test
    void topMerchantsRespectsTheLimit() {
        UUID userId = UUID.randomUUID();
        settle(userId, "Tesco", GROCERIES_MCC, 3_000L, "EUR", OCCURRED_AT);
        settle(userId, "Nandos", RESTAURANTS_MCC, 2_000L, "EUR", OCCURRED_AT);
        settle(userId, "Aldi", GROCERIES_MCC, 1_000L, "EUR", OCCURRED_AT);

        List<TopMerchant> top = queryService.topMerchants(userId, MONTH, 2);

        assertThat(top).hasSize(2);
        assertThat(top).extracting(TopMerchant::merchantName).containsExactly("Tesco", "Nandos");
    }

    @Test
    void topMerchantsNeverLeaksAnotherUsersSpend() {
        UUID userId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        settle(userId, "Tesco", GROCERIES_MCC, 1_000L, "EUR", OCCURRED_AT);
        settle(otherUser, "Nandos", RESTAURANTS_MCC, 9_999L, "EUR", OCCURRED_AT);

        List<TopMerchant> top = queryService.topMerchants(userId, MONTH, 10);

        assertThat(top).containsExactly(new TopMerchant("Tesco", "EUR", 1_000L, 1));
    }
}
