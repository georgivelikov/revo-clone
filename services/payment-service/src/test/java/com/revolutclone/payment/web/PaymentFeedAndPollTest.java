package com.revolutclone.payment.web;

import static com.revolutclone.payment.testsupport.AccountViewFixture.activeWallet;
import static com.revolutclone.payment.testsupport.TransferRequests.transferBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.payment.readmodel.AccountView;
import com.revolutclone.payment.readmodel.AccountViewRepository;
import com.revolutclone.payment.domain.PaymentRepository;
import com.revolutclone.payment.testsupport.NoOpJwtDecoderTestConfig;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task 09.6: {@code GET /api/payments} (the Activity screen's feed) and
 * {@code GET /api/payments/{id}} (task 03.10's WebSocket-drop poll fallback) were documented in
 * plans/phase-03-payments-and-ui.md's endpoint table since task 03.1 but never implemented -
 * {@link PaymentController} had only {@code @PostMapping}, so both 500'd
 * ({@code HttpRequestMethodNotSupportedException} falling through {@code ApiExceptionHandler}'s
 * generic case) until this task added {@link com.revolutclone.payment.service.PaymentQueryService}.
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
        "revolut.payment.fx.base-url=http://fx.invalid"
})
class PaymentFeedAndPollTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private AccountViewRepository accountViews;

    @Autowired
    private JsonMapper jsonMapper;

    private UUID alice;
    private UUID bob;
    private UUID carol;
    private AccountView aliceEur;
    private AccountView bobEur;
    private AccountView carolEur;

    @BeforeEach
    void seedThreePartiesWithEuroWallets() {
        payments.deleteAll();
        accountViews.deleteAll();

        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        carol = UUID.randomUUID();
        aliceEur = activeWallet(accountViews, alice, "EUR");
        bobEur = activeWallet(accountViews, bob, "EUR");
        carolEur = activeWallet(accountViews, carol, "EUR");
    }

    @Test
    void feedIncludesPaymentsSentAndReceivedButNotAThirdPartysOwn() throws Exception {
        UUID sentByAlice = postTransfer(alice, aliceEur, bobEur, 1_000L);
        UUID receivedByAlice = postTransfer(bob, bobEur, aliceEur, 500L);
        postTransfer(bob, bobEur, carolEur, 250L); // touches neither of Alice's accounts

        String body = mockMvc.perform(get("/api/payments")
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode feed = jsonMapper.readTree(body);
        assertThat(feed.isArray()).isTrue();
        assertThat(feed.size()).isEqualTo(2);
        assertThat(ids(feed)).containsExactlyInAnyOrder(sentByAlice.toString(), receivedByAlice.toString());
    }

    @Test
    void feedIsNewestFirst() throws Exception {
        UUID first = postTransfer(alice, aliceEur, bobEur, 100L);
        UUID second = postTransfer(alice, aliceEur, bobEur, 200L);

        mockMvc.perform(get("/api/payments")
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(second.toString()))
                .andExpect(jsonPath("$[1].paymentId").value(first.toString()));
    }

    @Test
    void feedIsEmptyForAUserWithNoPayments() throws Exception {
        mockMvc.perform(get("/api/payments")
                        .with(jwt().jwt(builder -> builder.subject(carol.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void pollFallbackReturnsAPaymentToItsSender() throws Exception {
        UUID paymentId = postTransfer(alice, aliceEur, bobEur, 1_500L);

        mockMvc.perform(get("/api/payments/" + paymentId)
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void pollFallbackAlsoReturnsAPaymentToItsRecipient() throws Exception {
        UUID paymentId = postTransfer(alice, aliceEur, bobEur, 1_500L);

        mockMvc.perform(get("/api/payments/" + paymentId)
                        .with(jwt().jwt(builder -> builder.subject(bob.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()));
    }

    @Test
    void pollFallbackIs404ForAPaymentNeitherPartyToNorAnUnknownId() throws Exception {
        UUID paymentId = postTransfer(alice, aliceEur, bobEur, 1_500L);

        mockMvc.perform(get("/api/payments/" + paymentId)
                        .with(jwt().jwt(builder -> builder.subject(carol.toString()))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/payments/" + UUID.randomUUID())
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isNotFound());
    }

    private UUID postTransfer(UUID initiator, AccountView source, AccountView target, long amountMinor)
            throws Exception {
        String body = mockMvc.perform(post("/api/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(source.getAccountId(), target.getAccountId(), amountMinor,
                                "EUR", "test"))
                        .with(jwt().jwt(builder -> builder.subject(initiator.toString()))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonMapper.readTree(body).get("paymentId").asString());
    }

    private static java.util.List<String> ids(JsonNode feed) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (JsonNode node : feed) {
            ids.add(node.get("paymentId").asString());
        }
        return ids;
    }
}
