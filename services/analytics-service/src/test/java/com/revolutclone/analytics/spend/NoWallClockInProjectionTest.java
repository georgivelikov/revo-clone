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
import java.time.ZoneOffset;
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
 * plans/phase-06-vaults-analytics.md's named test for task 06.3: "Events with old
 * {@code occurredAt} land in historical months." {@link com.revolutclone.analytics.consumer.MonthlySpendProjectionHandler}
 * has no {@link java.time.Clock} to inject in the first place - the point being proven is that the
 * bucket is derived solely from the envelope's own {@code occurredAt}, so an event that is years
 * old lands in that old month and nowhere near whatever month the test happens to run in.
 */
@Testcontainers
@SpringBootTest
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class NoWallClockInProjectionTest {

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
    void anEventFromYearsAgoLandsInItsOwnHistoricalMonthNotTheCurrentOne() {
        UUID authorizationId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Instant longAgo = Instant.parse("2021-03-15T10:00:00Z");
        LocalDate historicalMonth = LocalDate.of(2021, 3, 1);
        LocalDate currentMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);

        approvedHandler.handle(approved(authorizationId, cardId, userId, accountId, MERCHANT, MCC, 4_370L, "EUR",
                longAgo));
        settledHandler.handle(settled(authorizationId, cardId, userId, accountId, MERCHANT, MCC, 4_370L, "EUR",
                longAgo));

        MonthlySpend historical = monthlySpend
                .findById(new MonthlySpend.Key(userId, historicalMonth, Category.GROCERIES, "EUR"))
                .orElseThrow(() -> new AssertionError("expected a row bucketed into " + historicalMonth));
        assertThat(historical.getTotalMinor()).isEqualTo(4_370L);
        assertThat(historical.getTxnCount()).isEqualTo(1);

        // No row was created for "now" - the wall clock never entered into it, regardless of
        // which month this test actually runs in.
        assertThat(monthlySpend.findById(new MonthlySpend.Key(userId, currentMonth, Category.GROCERIES, "EUR")))
                .isEmpty();
    }
}
