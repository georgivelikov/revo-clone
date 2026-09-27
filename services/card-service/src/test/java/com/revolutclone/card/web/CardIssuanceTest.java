package com.revolutclone.card.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.card.crypto.PanCipher;
import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.pan.PanGenerator;
import com.revolutclone.card.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.messaging.outbox.OutboxEvent;
import com.revolutclone.messaging.outbox.OutboxEventRepository;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
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
 * plans/phase-05-cards.md task 2: "Issuance endpoint + CardIssuedV1." Exercises the full HTTP
 * stack against a real Postgres, same shape as payment-service's {@code TransferHappyPathTest}:
 * nothing mocked here except the JWT signature and the outbox publisher (disabled, per
 * {@code poll-delay-ms}, so this test asserts the row it would later pick up).
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
        // Neither client is called from an issuance test, but both have to bind: application.yml takes
        // each base URL from an environment variable with no default, so leaving them out would make
        // this test pass or fail depending on what happens to be exported on the machine running it.
        "revolut.card.ledger.base-url=http://ledger.invalid",
        "revolut.card.fx.base-url=http://fx.invalid"
})
class CardIssuanceTest {

    private static final String TOPIC = "card.authorization.v1";

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
    private OutboxEventRepository outbox;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private PanCipher panCipher;

    private UUID alice;
    private UUID aliceWallet;

    @BeforeEach
    void seed() {
        outbox.deleteAll();
        cards.deleteAll();
        alice = UUID.randomUUID();
        aliceWallet = UUID.randomUUID();
    }

    @Test
    void issuingACardCreatesAnActiveCardAndReturnsNoFullPan() throws Exception {
        String body = mockMvc.perform(post("/api/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","type":"VIRTUAL","label":"Groceries","pin":"1234"}
                                """.formatted(aliceWallet))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.userId").value(alice.toString()))
                .andExpect(jsonPath("$.accountId").value(aliceWallet.toString()))
                .andExpect(jsonPath("$.panLast4").isString())
                .andExpect(jsonPath("$.type").value("VIRTUAL"))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.label").value("Groceries"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = jsonMapper.readTree(body);
        // The full PAN never appears anywhere in the issuance response.
        assertThat(json.toString()).doesNotContain("999999");
        assertThat(json.get("panLast4").asString()).hasSize(4);

        UUID cardId = UUID.fromString(json.get("id").asString());
        Card persisted = cards.findById(cardId).orElseThrow();
        assertThat(persisted.getUserId()).isEqualTo(alice);
        assertThat(persisted.getAccountId()).isEqualTo(aliceWallet);

        // The stored ciphertext round-trips to a genuinely Luhn-valid PAN ending in the last4
        // the response advertised - proof issuance generated a real card number, not just a
        // display value.
        String pan = panCipher.decrypt(persisted.getPanEncrypted());
        assertThat(PanGenerator.isValidLuhn(pan)).isTrue();
        assertThat(pan).endsWith(persisted.getPanLast4());
    }

    @Test
    void issuingACardPublishesExactlyOneCardIssuedEvent() throws Exception {
        String body = postCard();
        UUID cardId = UUID.fromString(jsonMapper.readTree(body).get("id").asString());

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);

        OutboxEvent row = rows.getFirst();
        assertThat(row.getTopic()).isEqualTo(TOPIC);
        // CLAUDE.md hard rule 5: keyed by cardId, the entity whose ordering the authorization
        // flow (task 3) depends on.
        assertThat(row.getPartitionKey()).isEqualTo(cardId.toString());
        assertThat(row.getType()).isEqualTo("CardIssuedV1");

        JsonNode envelope = jsonMapper.readTree(row.getPayload());
        // CLAUDE.md hard rule 4: every event carries these four.
        assertThat(UUID.fromString(envelope.get("eventId").asString())).isNotNull();
        assertThat(Instant.parse(envelope.get("occurredAt").asString())).isNotNull();
        assertThat(envelope.get("correlationId").asString()).isNotBlank();
        assertThat(envelope.get("version").asInt()).isEqualTo(1);

        JsonNode payload = envelope.get("payload");
        assertThat(UUID.fromString(payload.get("cardId").asString())).isEqualTo(cardId);
        assertThat(UUID.fromString(payload.get("userId").asString())).isEqualTo(alice);
        assertThat(UUID.fromString(payload.get("accountId").asString())).isEqualTo(aliceWallet);
        assertThat(payload.get("type").asString()).isEqualTo("VIRTUAL");
        // The event never carries the full PAN either.
        assertThat(row.getPayload()).doesNotContain("999999");
    }

    private String postCard() throws Exception {
        return mockMvc.perform(post("/api/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","type":"VIRTUAL","label":"Groceries","pin":"1234"}
                                """.formatted(aliceWallet))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }
}
