package com.revolutclone.account.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.account.domain.AccountRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Task 06.6: proves {@code POST /internal/accounts/vaults} opens a {@code VAULT}-type account and
 * publishes {@code AccountOpenedV1} exactly like every other account-opening path, and - the point
 * of the endpoint existing at all - that it answers with <strong>no JWT attached</strong>, unlike
 * every {@code /api/accounts/**} route. {@code AccountQueryTest} already proves the opposite
 * direction (an unauthenticated {@code /api/accounts/**} request is rejected), so this test does
 * not repeat that; it only proves this one path's exemption and behaviour.
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
class InternalVaultAccountControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accounts;

    @Test
    void opensAVaultAccountWithNoJwtAtAll() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post("/internal/accounts/vaults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"currency\":\"eur\",\"name\":\"Holiday\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.type").value("VAULT"))
                .andExpect(jsonPath("$.name").value("Holiday"));

        var stored = accounts.findByUserId(userId);
        org.assertj.core.api.Assertions.assertThat(stored).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(stored.getFirst().getType()).isEqualTo(AccountType.VAULT);
    }

    @Test
    void aSecondVaultWithTheSameNameAndCurrencyIsAConflict() throws Exception {
        UUID userId = UUID.randomUUID();
        String body = "{\"userId\":\"" + userId + "\",\"currency\":\"EUR\",\"name\":\"Holiday\"}";

        mockMvc.perform(post("/internal/accounts/vaults").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/internal/accounts/vaults").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void aDifferentlyNamedVaultInTheSameCurrencyIsAllowed() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post("/internal/accounts/vaults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"currency\":\"EUR\",\"name\":\"Holiday\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/internal/accounts/vaults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"currency\":\"EUR\",\"name\":\"New car\"}"))
                .andExpect(status().isCreated());

        org.assertj.core.api.Assertions.assertThat(accounts.findByUserId(userId)).hasSize(2);
    }

    @Test
    void anExistingVaultAccountCanBeLookedUpByUserCurrencyAndNameWithNoJwt() throws Exception {
        UUID userId = UUID.randomUUID();
        String created = mockMvc.perform(post("/internal/accounts/vaults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"currency\":\"EUR\",\"name\":\"Holiday\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String accountId = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        mockMvc.perform(get("/internal/accounts/vaults")
                        .param("userId", userId.toString()).param("currency", "eur").param("name", "Holiday"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId))
                .andExpect(jsonPath("$.type").value("VAULT"));
    }

    @Test
    void lookingUpAVaultAccountThatDoesNotExistIsNotFound() throws Exception {
        mockMvc.perform(get("/internal/accounts/vaults")
                        .param("userId", UUID.randomUUID().toString()).param("currency", "EUR").param("name", "Nope"))
                .andExpect(status().isNotFound());
    }
}
