package com.revolutclone.card.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.pan.PanGenerator;
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
 * plans/phase-05-cards.md task 2: "reveal endpoint behind step-up," and the PAN handling note:
 * "return the full PAN from exactly one endpoint that requires a step-up." Proves the three
 * outcomes that matter: the right PIN reveals the card that was actually issued, the wrong PIN
 * is refused, and another user's card cannot be revealed at all.
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
class CardRevealTest {

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
    void theRightPinRevealsTheCardThatWasActuallyIssued() throws Exception {
        String issueBody = issueCard(alice, aliceWallet, "4321");
        JsonNode issued = jsonMapper.readTree(issueBody);
        UUID cardId = UUID.fromString(issued.get("id").asString());
        String panLast4 = issued.get("panLast4").asString();

        String revealBody = mockMvc.perform(post("/api/cards/" + cardId + "/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pin":"4321"}
                                """)
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pan").isString())
                .andExpect(jsonPath("$.cvv").isString())
                .andReturn().getResponse().getContentAsString();

        JsonNode revealed = jsonMapper.readTree(revealBody);
        String pan = revealed.get("pan").asString();
        assertPanMatchesIssuedCard(pan, panLast4);
        assertThat(revealed.get("cvv").asString()).hasSize(3);
        assertThat(revealed.get("expiryMonth").asInt()).isEqualTo(issued.get("expiryMonth").asInt());
        assertThat(revealed.get("expiryYear").asInt()).isEqualTo(issued.get("expiryYear").asInt());
    }

    @Test
    void theWrongPinIsRefusedAndNothingIsRevealed() throws Exception {
        String issueBody = issueCard(alice, aliceWallet, "4321");
        UUID cardId = UUID.fromString(jsonMapper.readTree(issueBody).get("id").asString());

        mockMvc.perform(post("/api/cards/" + cardId + "/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pin":"0000"}
                                """)
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(
                        "https://revolutclone.com/problems/step-up-failed"));
    }

    @Test
    void anotherUsersCardCannotBeRevealedAndReadsAsNotFound() throws Exception {
        String issueBody = issueCard(alice, aliceWallet, "4321");
        UUID cardId = UUID.fromString(jsonMapper.readTree(issueBody).get("id").asString());
        UUID mallory = UUID.randomUUID();

        mockMvc.perform(post("/api/cards/" + cardId + "/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pin":"4321"}
                                """)
                        .with(jwt().jwt(builder -> builder.subject(mallory.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(
                        "https://revolutclone.com/problems/card-not-found"));
    }

    private String issueCard(UUID userId, UUID accountId, String pin) throws Exception {
        return mockMvc.perform(post("/api/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","type":"VIRTUAL","label":"Groceries","pin":"%s"}
                                """.formatted(accountId, pin))
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static void assertPanMatchesIssuedCard(String pan, String panLast4) {
        assertThat(PanGenerator.isValidLuhn(pan)).isTrue();
        assertThat(pan).endsWith(panLast4);
    }
}
