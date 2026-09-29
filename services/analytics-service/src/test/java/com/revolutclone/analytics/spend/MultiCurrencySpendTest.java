package com.revolutclone.analytics.spend;

import static com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures.approved;
import static com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures.settled;
import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.analytics.categorisation.Category;
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
 * plans/phase-06-vaults-analytics.md's named test for task 06.3: "Per-currency totals stay
 * separate; no implicit summing." A user spending in EUR and GBP in the same month and category
 * must end up as two {@code monthly_spend} rows, one per currency - the phase's own words are
 * "Multi-currency is genuinely awkward here: a user spending in EUR, GBP and USD has no single
 * total. Store per-currency... Do not silently sum across currencies."
 */
@Testcontainers
@SpringBootTest
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class MultiCurrencySpendTest {

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

    @Test
    void spendingInTwoCurrenciesTheSameMonthAndCategoryStaysAsTwoRows() {
        UUID userId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2024-06-10T09:00:00Z");
        LocalDate month = LocalDate.of(2024, 6, 1);

        UUID eurAuthorization = UUID.randomUUID();
        UUID eurCard = UUID.randomUUID();
        UUID eurAccount = UUID.randomUUID();
        approvedHandler.handle(approved(eurAuthorization, eurCard, userId, eurAccount, MERCHANT, MCC, 4_370L, "EUR",
                occurredAt));
        settledHandler.handle(settled(eurAuthorization, eurCard, userId, eurAccount, MERCHANT, MCC, 4_370L, "EUR",
                occurredAt));

        UUID gbpAuthorization = UUID.randomUUID();
        UUID gbpCard = UUID.randomUUID();
        UUID gbpAccount = UUID.randomUUID();
        approvedHandler.handle(approved(gbpAuthorization, gbpCard, userId, gbpAccount, MERCHANT, MCC, 2_500L, "GBP",
                occurredAt));
        settledHandler.handle(settled(gbpAuthorization, gbpCard, userId, gbpAccount, MERCHANT, MCC, 2_500L, "GBP",
                occurredAt));

        MonthlySpend eurRow = monthlySpend.findById(new MonthlySpend.Key(userId, month, Category.GROCERIES, "EUR"))
                .orElseThrow();
        assertThat(eurRow.getTotalMinor()).isEqualTo(4_370L);
        assertThat(eurRow.getTxnCount()).isEqualTo(1);

        MonthlySpend gbpRow = monthlySpend.findById(new MonthlySpend.Key(userId, month, Category.GROCERIES, "GBP"))
                .orElseThrow();
        assertThat(gbpRow.getTotalMinor()).isEqualTo(2_500L);
        assertThat(gbpRow.getTxnCount()).isEqualTo(1);

        // Exactly two rows for this (user, month, category) - never one row silently summing
        // 4_370 EUR and 2_500 GBP into a meaningless 6_870.
        List<MonthlySpend> allCurrenciesThisMonth = monthlySpend.findByUserIdAndMonthAndCategory(userId, month,
                Category.GROCERIES);
        assertThat(allCurrenciesThisMonth).hasSize(2);
        assertThat(allCurrenciesThisMonth).extracting(MonthlySpend::getCurrency).containsExactlyInAnyOrder("EUR",
                "GBP");
    }
}
