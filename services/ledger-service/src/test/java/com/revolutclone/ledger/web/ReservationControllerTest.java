package com.revolutclone.ledger.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.testsupport.NoOpJwtDecoderTestConfig;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-02-ledger.md's {@code ### API}: hold / capture / release, end to end through the
 * HTTP layer. Business-rule rejections already surface as the correct status because
 * {@code ReservationService}'s own exceptions extend {@code ApiException}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class ReservationControllerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private AccountBalanceRepository accountBalanceRepository;

    @Test
    void holdCaptureReleaseFlowThroughHttp() throws Exception {
        UUID wallet = openAccount(LedgerAccountType.USER_WALLET, "user:" + UUID.randomUUID(), "EUR", 10_000);
        UUID merchant = openAccount(LedgerAccountType.EXTERNAL_CLEARING, "system:test-merchant", "EUR", 0);

        String holdBody = """
                {
                  "ledgerAccountId": "%s",
                  "amountMinor": 3000,
                  "currency": "EUR",
                  "referenceId": "%s",
                  "idempotencyKey": "cardauth:%s",
                  "expiresAt": "2099-01-01T00:00:00Z"
                }
                """.formatted(wallet, UUID.randomUUID(), UUID.randomUUID());

        String holdResponse = mockMvc.perform(post("/internal/ledger/reservations")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("HELD"))
                .andReturn().getResponse().getContentAsString();
        String reservationId = JsonPath.read(holdResponse, "$.id");

        org.assertj.core.api.Assertions.assertThat(
                accountBalanceRepository.findById(wallet).orElseThrow().getReservedMinor()).isEqualTo(3000);

        String captureBody = """
                {
                  "counterpartyAccountId": "%s",
                  "amountMinor": 2000,
                  "type": "CARD_SETTLEMENT",
                  "idempotencyKey": "cardauth:%s-capture"
                }
                """.formatted(merchant, reservationId);

        mockMvc.perform(post("/internal/ledger/reservations/" + reservationId + "/capture")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(captureBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedMinor").value(2000))
                .andExpect(jsonPath("$.releasedRemainderMinor").value(1000));

        AccountBalance afterCapture = accountBalanceRepository.findById(wallet).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(afterCapture.getReservedMinor()).isZero();
        org.assertj.core.api.Assertions.assertThat(afterCapture.getAvailableMinor()).isEqualTo(8000);
    }

    @Test
    void releasingAnUnknownReservationReturns404() throws Exception {
        mockMvc.perform(post("/internal/ledger/reservations/" + UUID.randomUUID() + "/release").with(jwt()))
                .andExpect(status().isNotFound());
    }

    private UUID openAccount(LedgerAccountType type, String ownerRef, String currency, long startingAvailableMinor) {
        LedgerAccount account = new LedgerAccount(UUID.randomUUID(), ownerRef, type, currency);
        ledgerAccountRepository.saveAndFlush(account);
        AccountBalance balance = new AccountBalance(account.getId(), currency);
        balance.creditAvailable(startingAvailableMinor);
        accountBalanceRepository.saveAndFlush(balance);
        return account.getId();
    }
}
