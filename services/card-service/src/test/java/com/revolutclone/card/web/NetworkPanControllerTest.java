package com.revolutclone.card.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Task 05.8: {@code simulator-service} calls {@code GET /internal/cards/{cardId}/pan-for-network}
 * with no JWT at all - see {@code NetworkPanController}'s javadoc for why that is the deliberate
 * design rather than an oversight. This proves both that the plaintext PAN comes back correctly and
 * that the exemption in {@code SecurityConfig} actually covers the path (a regression here would
 * otherwise surface only as the simulator's spend endpoint mysteriously 401-ing).
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
class NetworkPanControllerTest {

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
    void returnsTheRealPanWithNoAuthenticationAtAll() throws Exception {
        String issueBody = issueCard(alice, aliceWallet, "4321");
        JsonNode issued = jsonMapper.readTree(issueBody);
        UUID cardId = UUID.fromString(issued.get("id").asString());
        String panLast4 = issued.get("panLast4").asString();

        String body = mockMvc.perform(get("/internal/cards/" + cardId + "/pan-for-network"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pan").isString())
                .andReturn().getResponse().getContentAsString();

        String pan = jsonMapper.readTree(body).get("pan").asString();
        assertThat(PanGenerator.isValidLuhn(pan)).isTrue();
        assertThat(pan).endsWith(panLast4);
    }

    @Test
    void anUnknownCardIdIsNotFound() throws Exception {
        mockMvc.perform(get("/internal/cards/" + UUID.randomUUID() + "/pan-for-network"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://revolutclone.com/problems/card-not-found"));
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
}
