package com.revolutclone.ledger.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** plans/phase-02-ledger.md's {@code ### API}: current balance and the paginated statement. */
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
class AccountQueryControllerTest {

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
    void balanceReturnsCurrentAvailableAndReserved() throws Exception {
        UUID account = openAccount(1_500, 250);

        mockMvc.perform(get("/internal/ledger/accounts/" + account + "/balance").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableMinor").value(1500))
                .andExpect(jsonPath("$.reservedMinor").value(250))
                .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void balanceForUnknownAccountReturns404() throws Exception {
        mockMvc.perform(get("/internal/ledger/accounts/" + UUID.randomUUID() + "/balance").with(jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    void postingsForAFreshAccountWithNoActivityIsAnEmptyList() throws Exception {
        UUID account = openAccount(0, 0);

        mockMvc.perform(get("/internal/ledger/accounts/" + account + "/postings").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private UUID openAccount(long availableMinor, long reservedMinor) {
        LedgerAccount account = new LedgerAccount(UUID.randomUUID(), "user:" + UUID.randomUUID(),
                LedgerAccountType.USER_WALLET, "EUR");
        ledgerAccountRepository.saveAndFlush(account);
        AccountBalance balance = new AccountBalance(account.getId(), "EUR");
        // moveToReserved(x) does available -= x, reserved += x - crediting the combined total
        // first and then moving reservedMinor into reserved lands both columns at the values a
        // caller actually asked for, without a raw setter this entity deliberately doesn't have.
        balance.creditAvailable(availableMinor + reservedMinor);
        balance.moveToReserved(reservedMinor);
        accountBalanceRepository.saveAndFlush(balance);
        return account.getId();
    }
}
