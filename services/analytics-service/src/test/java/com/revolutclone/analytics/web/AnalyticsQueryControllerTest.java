package com.revolutclone.analytics.web;

import static com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures.approved;
import static com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures.settled;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.analytics.consumer.AuthorizationApprovedEventHandler;
import com.revolutclone.analytics.consumer.AuthorizationSettledEventHandler;
import com.revolutclone.analytics.testsupport.NoOpJwtDecoderTestConfig;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Task 06.8's four query endpoints, exercised over real HTTP through {@link AnalyticsQueryController}
 * - wiring, request-param binding and status codes, at the level {@code AccountQueryTest} already
 * established for this codebase's other "scoped to the caller" query endpoints. Business-logic
 * correctness (aggregation, multi-currency, ranking) is {@code AnalyticsQueryServiceTest}'s job;
 * this class only proves the controller calls into that service correctly and surfaces the right
 * HTTP status.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class AnalyticsQueryControllerTest {

    private static final String GROCERIES_MCC = "5411";
    private static final Instant OCCURRED_AT = Instant.parse("2024-06-10T09:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthorizationApprovedEventHandler approvedHandler;

    @Autowired
    private AuthorizationSettledEventHandler settledHandler;

    private void settle(UUID userId, String merchant, long amountMinor, String currency) {
        UUID authorizationId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        approvedHandler.handle(approved(authorizationId, cardId, userId, accountId, merchant, GROCERIES_MCC,
                amountMinor, currency, OCCURRED_AT));
        settledHandler.handle(settled(authorizationId, cardId, userId, accountId, merchant, GROCERIES_MCC,
                amountMinor, currency, OCCURRED_AT));
    }

    @Test
    void summaryReturnsOnlyTheCallersOwnMonth() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        settle(userId, "Tesco", 4_370L, "EUR");
        settle(otherUser, "Tesco", 9_999L, "EUR");

        mockMvc.perform(get("/api/analytics/summary")
                        .param("month", "2024-06-15")
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2024-06-01"))
                .andExpect(jsonPath("$.categories.length()").value(1))
                .andExpect(jsonPath("$.categories[0].category").value("Groceries"))
                .andExpect(jsonPath("$.categories[0].currency").value("EUR"))
                .andExpect(jsonPath("$.categories[0].totalMinor").value(4370));
    }

    @Test
    void summaryForAMonthWithNoActivityIsAnEmptyOkNotANotFound() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(get("/api/analytics/summary")
                        .param("month", "2030-01-01")
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(0));
    }

    @Test
    void categoryDrillDownReturnsTheCallersSettledTransactions() throws Exception {
        UUID userId = UUID.randomUUID();
        settle(userId, "Tesco", 4_370L, "EUR");

        mockMvc.perform(get("/api/analytics/categories/Groceries/transactions")
                        .param("month", "2024-06-01")
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].merchantName").value("Tesco"))
                .andExpect(jsonPath("$[0].amountMinor").value(4370))
                .andExpect(jsonPath("$[0].currency").value("EUR"));
    }

    @Test
    void categoryDrillDownRejectsAnUnknownCategoryWithBadRequest() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(get("/api/analytics/categories/NotACategory/transactions")
                        .param("month", "2024-06-01")
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void trendReturnsTotalsUpToAndIncludingAsOf() throws Exception {
        UUID userId = UUID.randomUUID();
        settle(userId, "Tesco", 1_000L, "EUR");

        mockMvc.perform(get("/api/analytics/trend")
                        .param("asOf", "2024-06-30")
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].month").value("2024-06-01"))
                .andExpect(jsonPath("$[0].currency").value("EUR"))
                .andExpect(jsonPath("$[0].totalMinor").value(1000));
    }

    @Test
    void topMerchantsRespectsTheLimitParameter() throws Exception {
        UUID userId = UUID.randomUUID();
        settle(userId, "Tesco", 3_000L, "EUR");
        settle(userId, "Aldi", 2_000L, "EUR");
        settle(userId, "Lidl", 1_000L, "EUR");

        mockMvc.perform(get("/api/analytics/merchants/top")
                        .param("month", "2024-06-01")
                        .param("limit", "2")
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].merchantName").value("Tesco"))
                .andExpect(jsonPath("$[1].merchantName").value("Aldi"));
    }
}
