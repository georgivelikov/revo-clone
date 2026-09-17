package com.revolutclone.account.web;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.account.domain.Account;
import com.revolutclone.account.domain.AccountRepository;
import com.revolutclone.account.domain.AccountState;
import com.revolutclone.account.domain.AccountType;
import com.revolutclone.account.testsupport.NoOpJwtDecoderTestConfig;
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
 * plans/phase-01-identity-account-gateway.md's Tests table: "GET /accounts/me returns only the
 * caller's accounts, never another user's." Inserts accounts for two different users directly
 * (no need for a real registration flow) and proves isolation through two different mocked
 * JWTs, and that one user's account id 404s for a different caller rather than 403ing - see
 * {@link com.revolutclone.account.error.AccountNotFoundException}.
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
class AccountQueryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void meReturnsOnlyTheCallersOwnAccounts() throws Exception {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        Account accountA = seedWallet(userA, "EUR");
        seedWallet(userA, "GBP");
        seedWallet(userB, "EUR");

        mockMvc.perform(get("/api/accounts/me").with(jwt().jwt(builder -> builder.subject(userA.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").value(userA.toString()))
                .andExpect(jsonPath("$[1].userId").value(userA.toString()));

        mockMvc.perform(get("/api/accounts/me").with(jwt().jwt(builder -> builder.subject(userB.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(userB.toString()));

        // Sanity check that the fixture is wired the way the assertions above assume.
        org.assertj.core.api.Assertions.assertThat(accountA.getUserId()).isEqualTo(userA);
    }

    @Test
    void byIdReturnsNotFoundRatherThanTheOtherUsersAccount() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        Account account = seedWallet(owner, "USD");

        mockMvc.perform(get("/api/accounts/" + account.getId())
                        .with(jwt().jwt(builder -> builder.subject(owner.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(account.getId().toString()));

        mockMvc.perform(get("/api/accounts/" + account.getId())
                        .with(jwt().jwt(builder -> builder.subject(intruder.toString()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void balanceIsAlwaysExplicitlyNull() throws Exception {
        UUID userId = UUID.randomUUID();
        seedWallet(userId, "EUR");

        mockMvc.perform(get("/api/accounts/me").with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].balance").value(nullValue()))
                .andExpect(jsonPath("$[0].currency").value("EUR"));
    }

    private Account seedWallet(UUID userId, String currency) {
        return accountRepository.saveAndFlush(
                new Account(UUID.randomUUID(), userId, currency, AccountType.WALLET, "", AccountState.ACTIVE));
    }
}
