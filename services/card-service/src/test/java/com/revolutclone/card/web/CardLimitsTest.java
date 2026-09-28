package com.revolutclone.card.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.card.domain.CardLimitRepository;
import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.readmodel.FundingAccount;
import com.revolutclone.card.readmodel.FundingAccountRepository;
import com.revolutclone.card.testsupport.NoOpJwtDecoderTestConfig;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * plans/phase-05-cards.md task 9's limits endpoints. The currency in every response is EUR without
 * ever being asked for - {@link CardLimitService}'s whole point is that a limit's currency comes
 * from the card's own funding wallet, never from the caller.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        "revolut.card.ledger.base-url=http://ledger.invalid",
        "revolut.card.fx.base-url=http://fx.invalid"
})
class CardLimitsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void cryptoKeys(DynamicPropertyRegistry registry) {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        registry.add("revolut.card.crypto.encryption-key", () -> key);
        registry.add("revolut.card.crypto.pan-hash-key", () -> key);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CardRepository cards;

    @Autowired
    private CardLimitRepository limits;

    @Autowired
    private FundingAccountRepository fundingAccounts;

    @Autowired
    private JsonMapper jsonMapper;

    private UUID alice;
    private UUID aliceWallet;

    @BeforeEach
    void seed() {
        limits.deleteAll();
        cards.deleteAll();
        fundingAccounts.deleteAll();
        alice = UUID.randomUUID();
        aliceWallet = UUID.randomUUID();
    }

    @Test
    void settingALimitEchoesTheWalletsCurrencyNeverAskedFor() throws Exception {
        fundWallet(aliceWallet, "EUR");
        UUID cardId = issueCard(alice, aliceWallet);

        mockMvc.perform(put("/api/cards/" + cardId + "/limits/DAILY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor":50000}
                                """)
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("DAILY"))
                .andExpect(jsonPath("$.amountMinor").value(50000))
                .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void aSecondUpsertOverwritesTheFirstRatherThanAddingASecondRow() throws Exception {
        fundWallet(aliceWallet, "EUR");
        UUID cardId = issueCard(alice, aliceWallet);

        putLimit(cardId, "DAILY", 10_000);
        putLimit(cardId, "DAILY", 25_000).andExpect(jsonPath("$.amountMinor").value(25000));

        String body = list(cardId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode list = jsonMapper.readTree(body);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("amountMinor").asLong()).isEqualTo(25_000L);
    }

    @Test
    void removingALimitMeansNoCeilingRatherThanAHugeOne() throws Exception {
        fundWallet(aliceWallet, "EUR");
        UUID cardId = issueCard(alice, aliceWallet);
        putLimit(cardId, "DAILY", 10_000);

        mockMvc.perform(delete("/api/cards/" + cardId + "/limits/DAILY")
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isNoContent());

        list(cardId).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void removingAnAlreadyAbsentLimitIsIdempotent() throws Exception {
        fundWallet(aliceWallet, "EUR");
        UUID cardId = issueCard(alice, aliceWallet);

        mockMvc.perform(delete("/api/cards/" + cardId + "/limits/MONTHLY")
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isNoContent());
    }

    @Test
    void settingALimitBeforeTheWalletProjectionArrivesIsServiceUnavailable() throws Exception {
        // No fundWallet() call - the projection has not caught up, same gap FundingWalletResolver
        // documents for the authorization path.
        UUID cardId = issueCard(alice, aliceWallet);

        mockMvc.perform(put("/api/cards/" + cardId + "/limits/DAILY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor":50000}
                                """)
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value(
                        "https://revolutclone.com/problems/wallet-currency-unknown"));
    }

    @Test
    void anotherUsersCardLimitsCannotBeSetAndReadAsNotFound() throws Exception {
        fundWallet(aliceWallet, "EUR");
        UUID cardId = issueCard(alice, aliceWallet);
        UUID mallory = UUID.randomUUID();

        mockMvc.perform(put("/api/cards/" + cardId + "/limits/DAILY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor":50000}
                                """)
                        .with(jwt().jwt(builder -> builder.subject(mallory.toString()))))
                .andExpect(status().isNotFound());
        assertThat(limits.findByCardId(cardId)).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions putLimit(UUID cardId, String period, long amountMinor)
            throws Exception {
        return mockMvc.perform(put("/api/cards/" + cardId + "/limits/" + period)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":" + amountMinor + "}")
                .with(jwt().jwt(builder -> builder.subject(alice.toString()))));
    }

    private org.springframework.test.web.servlet.ResultActions list(UUID cardId) throws Exception {
        return mockMvc.perform(get("/api/cards/" + cardId + "/limits")
                .with(jwt().jwt(builder -> builder.subject(alice.toString()))));
    }

    private void fundWallet(UUID accountId, String currency) {
        FundingAccount account = new FundingAccount(accountId);
        account.apply(alice, currency, FundingAccount.WALLET, Instant.now());
        fundingAccounts.saveAndFlush(account);
    }

    private UUID issueCard(UUID userId, UUID accountId) throws Exception {
        String body = mockMvc.perform(post("/api/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","type":"VIRTUAL","label":"Groceries","pin":"4321"}
                                """.formatted(accountId))
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode issued = jsonMapper.readTree(body);
        return UUID.fromString(issued.get("id").asString());
    }
}
