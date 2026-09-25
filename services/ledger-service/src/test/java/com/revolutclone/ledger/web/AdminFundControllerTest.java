package com.revolutclone.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Task 02.11: "A funding endpoint (EXTERNAL_CLEARING -> wallet) so later phases have money to
 * move." Uses the real EXTERNAL_CLEARING/EUR account task 02.7's V5__system_accounts.sql seeds
 * into every fresh migration - no fake clearing account needed, unlike
 * {@link LedgerEntryControllerTest}, which predates task 02.7 and so has to fabricate its own.
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
class AdminFundControllerTest {

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
    void fundingAWalletCreditsItByExactlyTheRequestedAmount() throws Exception {
        UUID wallet = openZeroBalanceWallet("EUR");

        mockMvc.perform(post("/internal/ledger/admin/fund/" + wallet)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor": 100000, "currency": "EUR"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.postings.length()").value(2));

        assertThat(accountBalanceRepository.findById(wallet).orElseThrow().getAvailableMinor()).isEqualTo(100_000);
    }

    @Test
    void currencyMismatchReturns400() throws Exception {
        UUID eurWallet = openZeroBalanceWallet("EUR");

        mockMvc.perform(post("/internal/ledger/admin/fund/" + eurWallet)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor": 1000, "currency": "GBP"}
                                """))
                .andExpect(status().isBadRequest());
    }

    private UUID openZeroBalanceWallet(String currency) {
        LedgerAccount account = new LedgerAccount(UUID.randomUUID(), "user:" + UUID.randomUUID(),
                LedgerAccountType.USER_WALLET, currency);
        ledgerAccountRepository.saveAndFlush(account);
        accountBalanceRepository.saveAndFlush(new AccountBalance(account.getId(), currency));
        return account.getId();
    }
}
