package com.revolutclone.card.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.testsupport.NoOpJwtDecoderTestConfig;
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
 * plans/phase-05-cards.md task 9: "Freeze/unfreeze." A frozen card declining the next
 * authorization is already proven by {@code AuthorizationDeclinedFrozenTest} (task 05.3) with the
 * card seeded straight into the table - this class is about the endpoint that gets a card into
 * that state in the first place.
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
class CardFreezeTest {

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
    private JsonMapper jsonMapper;

    private UUID alice;
    private UUID aliceWallet;

    @BeforeEach
    void seed() {
        cards.deleteAll();
        alice = UUID.randomUUID();
        aliceWallet = UUID.randomUUID();
    }

    @Test
    void freezingAnActiveCardTurnsItFrozen() throws Exception {
        UUID cardId = issueCard(alice, aliceWallet);

        mockMvc.perform(post("/api/cards/" + cardId + "/freeze")
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FROZEN"));
    }

    @Test
    void freezingIsIdempotent() throws Exception {
        UUID cardId = issueCard(alice, aliceWallet);

        freeze(cardId, alice).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("FROZEN"));
        freeze(cardId, alice).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("FROZEN"));
    }

    @Test
    void unfreezingTurnsItActiveAgain() throws Exception {
        UUID cardId = issueCard(alice, aliceWallet);
        freeze(cardId, alice).andExpect(status().isOk());

        mockMvc.perform(post("/api/cards/" + cardId + "/unfreeze")
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"));
    }

    @Test
    void unfreezingAnAlreadyActiveCardIsANoOp() throws Exception {
        UUID cardId = issueCard(alice, aliceWallet);

        mockMvc.perform(post("/api/cards/" + cardId + "/unfreeze")
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"));
    }

    @Test
    void anotherUsersCardCannotBeFrozenAndReadsAsNotFound() throws Exception {
        UUID cardId = issueCard(alice, aliceWallet);
        UUID mallory = UUID.randomUUID();

        mockMvc.perform(post("/api/cards/" + cardId + "/freeze")
                        .with(jwt().jwt(builder -> builder.subject(mallory.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://revolutclone.com/problems/card-not-found"));

        assertThat(cards.findById(cardId).orElseThrow().getState().name()).isEqualTo("ACTIVE");
    }

    private org.springframework.test.web.servlet.ResultActions freeze(UUID cardId, UUID userId) throws Exception {
        return mockMvc.perform(post("/api/cards/" + cardId + "/freeze")
                .with(jwt().jwt(builder -> builder.subject(userId.toString()))));
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
