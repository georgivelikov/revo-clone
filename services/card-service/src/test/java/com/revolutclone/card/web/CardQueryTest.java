package com.revolutclone.card.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.CardAuthorizationRepository;
import com.revolutclone.card.domain.CardRepository;
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
 * Task 05.10's Card screen needs two reads that did not exist before it: every card a holder has
 * issued, and one card's spend feed - "pending clearly distinct from settled."
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(com.revolutclone.card.testsupport.NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        "revolut.card.ledger.base-url=http://ledger.invalid",
        "revolut.card.fx.base-url=http://fx.invalid"
})
class CardQueryTest {

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
    private CardAuthorizationRepository authorizations;

    @Autowired
    private JsonMapper jsonMapper;

    private UUID alice;
    private UUID aliceWallet;

    @BeforeEach
    void seed() {
        authorizations.deleteAll();
        cards.deleteAll();
        alice = UUID.randomUUID();
        aliceWallet = UUID.randomUUID();
    }

    @Test
    void listsOnlyThisHoldersCards() throws Exception {
        issueCard(alice, aliceWallet);
        issueCard(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(get("/api/cards").with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void theSpendFeedShowsPendingAndSettledDistinctly() throws Exception {
        UUID cardId = issueCard(alice, aliceWallet);
        authorizations.saveAndFlush(CardAuthorization.approved(UUID.randomUUID(), cardId, "net-1", "Tesco",
                "5411", "IE", 4_250L, "EUR", 4_250L, "EUR", null, UUID.randomUUID(), Instant.now()));
        CardAuthorization settled = CardAuthorization.approved(UUID.randomUUID(), cardId, "net-2", "IKEA",
                "5999", "IE", 8_000L, "EUR", 8_000L, "EUR", null, UUID.randomUUID(), Instant.now());
        settled.markSettled(8_000L, 0L, UUID.randomUUID(), null, Instant.now());
        authorizations.saveAndFlush(settled);

        String body = mockMvc.perform(get("/api/cards/" + cardId + "/authorizations")
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode list = jsonMapper.readTree(body);
        JsonNode pending = find(list, "net-1");
        JsonNode isSettled = find(list, "net-2");
        org.assertj.core.api.Assertions.assertThat(pending.get("state").asString()).isEqualTo("APPROVED");
        org.assertj.core.api.Assertions.assertThat(isSettled.get("state").asString()).isEqualTo("SETTLED");
        org.assertj.core.api.Assertions.assertThat(isSettled.get("settledMinor").asLong()).isEqualTo(8_000L);
    }

    @Test
    void anotherUsersSpendFeedCannotBeReadAndReadsAsNotFound() throws Exception {
        UUID cardId = issueCard(alice, aliceWallet);
        UUID mallory = UUID.randomUUID();

        mockMvc.perform(get("/api/cards/" + cardId + "/authorizations")
                        .with(jwt().jwt(builder -> builder.subject(mallory.toString()))))
                .andExpect(status().isNotFound());
    }

    private static JsonNode find(JsonNode list, String networkRef) {
        for (JsonNode node : list) {
            if (node.get("networkRef").asString().equals(networkRef)) {
                return node;
            }
        }
        throw new AssertionError("no entry for " + networkRef);
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
