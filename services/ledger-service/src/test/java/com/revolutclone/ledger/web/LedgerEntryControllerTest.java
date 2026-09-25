package com.revolutclone.ledger.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.testsupport.NoOpJwtDecoderTestConfig;
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
 * plans/phase-02-ledger.md's {@code ### API}: {@code POST /internal/ledger/entries}. Happy path
 * (a balanced ADJUSTMENT entry posts and both balances move) and the 409 rejection path
 * ({@code INSUFFICIENT_FUNDS}, mapped through {@link com.revolutclone.ledger.web.error
 * .EntryRejectedException}).
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
class LedgerEntryControllerTest {

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
    void postingABalancedEntryReturns201AndMovesBothAccounts() throws Exception {
        UUID clearing = openAccount(LedgerAccountType.EXTERNAL_CLEARING, "system:test-clearing", "EUR", 0);
        UUID wallet = openAccount(LedgerAccountType.USER_WALLET, "user:" + UUID.randomUUID(), "EUR", 0);

        String body = """
                {
                  "idempotencyKey": "test-fund-1",
                  "type": "ADJUSTMENT",
                  "postings": [
                    {"ledgerAccountId": "%s", "direction": "DEBIT", "amountMinor": 10000, "currency": "EUR"},
                    {"ledgerAccountId": "%s", "direction": "CREDIT", "amountMinor": 10000, "currency": "EUR"}
                  ]
                }
                """.formatted(clearing, wallet);

        mockMvc.perform(post("/internal/ledger/entries")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotencyKey").value("test-fund-1"))
                .andExpect(jsonPath("$.postings.length()").value(2));

        org.assertj.core.api.Assertions.assertThat(
                accountBalanceRepository.findById(wallet).orElseThrow().getAvailableMinor()).isEqualTo(10000);
    }

    @Test
    void debitBeyondAvailableReturns409() throws Exception {
        UUID clearing = openAccount(LedgerAccountType.EXTERNAL_CLEARING, "system:test-clearing-2", "EUR", 0);
        UUID wallet = openAccount(LedgerAccountType.USER_WALLET, "user:" + UUID.randomUUID(), "EUR", 500);

        String body = """
                {
                  "idempotencyKey": "test-overspend-1",
                  "type": "ADJUSTMENT",
                  "postings": [
                    {"ledgerAccountId": "%s", "direction": "DEBIT", "amountMinor": 100000, "currency": "EUR"},
                    {"ledgerAccountId": "%s", "direction": "CREDIT", "amountMinor": 100000, "currency": "EUR"}
                  ]
                }
                """.formatted(wallet, clearing);

        mockMvc.perform(post("/internal/ledger/entries")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void fewerThanTwoPostingsReturns400() throws Exception {
        UUID wallet = openAccount(LedgerAccountType.USER_WALLET, "user:" + UUID.randomUUID(), "EUR", 0);

        String body = """
                {
                  "idempotencyKey": "test-single-posting",
                  "type": "ADJUSTMENT",
                  "postings": [
                    {"ledgerAccountId": "%s", "direction": "CREDIT", "amountMinor": 100, "currency": "EUR"}
                  ]
                }
                """.formatted(wallet);

        mockMvc.perform(post("/internal/ledger/entries")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
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
