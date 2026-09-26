package com.revolutclone.gateway.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.gateway.StubJwtDecoderConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The double-submitted transfer, from plans/phase-03-payments-and-ui.md's test table: "same key
 * twice concurrently -> one payment, one ledger entry, identical responses".
 *
 * <p>The assertion that carries all three of those is <strong>the downstream service was called
 * exactly once</strong>. One accepted {@code POST /api/payments} is one {@code Payment} row, and
 * payment-service derives the ledger's idempotency key from the payment id
 * ({@code payment:<paymentId>}), so one payment is one journal entry. This test therefore proves
 * the edge layer's half - no duplicate ever reaches the service - while the service's own half
 * (the {@code unique (initiator_user_id, idempotency_key)} constraint that still holds when Redis
 * has been flushed) is {@code PaymentInitiator}'s, and the ledger's half is
 * {@code LedgerEventRedeliveryTest}'s.
 *
 * <p>Real Redis via Testcontainers, not a mock: {@code SET NX} against a real server is the thing
 * that makes "first request wins" atomic, and a fake that returns whatever the test wants would
 * assert nothing. Real concurrency too - the stub downstream holds the first request open while
 * the duplicate is sent, which is the only way to reach the in-flight case rather than the
 * already-completed one.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(StubJwtDecoderConfig.class)
// Not a src/test/resources/application.yml: that replaces main's rather than merging with it and
// would drop the gateway's routes entirely. See docs/spring-boot-4-notes.md.
@TestPropertySource(properties = {
        "OIDC_ISSUER_URI=https://example.invalid/realms/test",
        "CORS_ALLOWED_ORIGIN=http://localhost:5173"
})
class DoubleSubmitTest {

    private static final String TRANSFER_BODY = """
            {"sourceAccountId":"11111111-1111-1111-1111-111111111111",\
            "targetAccountId":"22222222-2222-2222-2222-222222222222",\
            "amountMinor":1000,"currency":"EUR","reference":"rent"}""";

    @Container
    @ServiceConnection("redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static StubPaymentService downstream;

    @Autowired
    private WebTestClient client;

    @BeforeAll
    static void startDownstream() throws Exception {
        downstream = new StubPaymentService();
    }

    @AfterAll
    static void stopDownstream() {
        downstream.close();
    }

    @DynamicPropertySource
    static void routeToStub(DynamicPropertyRegistry registry) {
        registry.add("revolut.gateway.routes.payment-service-uri", downstream::uri);
        registry.add("revolut.gateway.routes.identity-service-uri", downstream::uri);
        registry.add("revolut.gateway.routes.account-service-uri", downstream::uri);
        registry.add("revolut.gateway.routes.ledger-service-uri", downstream::uri);
        registry.add("revolut.gateway.routes.notification-service-uri", downstream::uri);
    }

    @Test
    void concurrentDuplicateIsRejectedAndTheCompletedDuplicateReplaysTheSameResponse() throws Exception {
        String key = UUID.randomUUID().toString();
        int downstreamCallsBefore = downstream.postCount();
        CountDownLatch releaseDownstream = new CountDownLatch(1);
        downstream.holdRequestsUntil(releaseDownstream);

        // The first request reaches the stub and is held there, so it is genuinely in flight.
        CompletableFuture<EntityExchangeResult<byte[]>> first = CompletableFuture.supplyAsync(() ->
                post(key).exchange()
                        .expectStatus().isAccepted()
                        .expectBody().returnResult());
        assertThat(downstream.awaitRequestCount(downstreamCallsBefore + 1, Duration.ofSeconds(10)))
                .as("the first request should have reached the downstream service")
                .isTrue();

        // The concurrent duplicate cannot be answered with a response that does not exist yet.
        post(key).exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().doesNotExist(IdempotencyFilter.REPLAYED_HEADER)
                .expectBody()
                .jsonPath("$.title").isEqualTo("IN_PROGRESS")
                .jsonPath("$.type").isEqualTo("https://revolutclone.com/problems/idempotency-in-progress");

        releaseDownstream.countDown();
        String firstResponse = bodyOf(first.get(15, TimeUnit.SECONDS));

        // Now that the first request has been answered, the duplicate gets that answer verbatim.
        EntityExchangeResult<byte[]> replayed = post(key).exchange()
                .expectStatus().isAccepted()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals(IdempotencyFilter.REPLAYED_HEADER, "true")
                .expectBody().returnResult();
        assertThat(bodyOf(replayed))
                .as("a completed duplicate must replay the stored response body verbatim")
                .isEqualTo(firstResponse);

        assertThat(downstream.postCount())
                .as("exactly one payment: neither duplicate may reach payment-service")
                .isEqualTo(downstreamCallsBefore + 1);
    }

    @Test
    void aDifferentKeyIsANewRequest() {
        // Guards against the filter over-reaching: idempotency is keyed on the header, so two
        // genuinely distinct transfers must both get through.
        int before = downstream.postCount();
        downstream.holdRequestsUntil(null);

        post(UUID.randomUUID().toString()).exchange().expectStatus().isAccepted();
        post(UUID.randomUUID().toString()).exchange().expectStatus().isAccepted();

        assertThat(downstream.postCount()).isEqualTo(before + 2);
    }

    @Test
    void aMoneyMovingPostWithoutTheHeaderIsRejectedAtTheEdge() {
        // CLAUDE.md hard rule 3 - and the request must not reach payment-service at all.
        int before = downstream.postCount();
        downstream.holdRequestsUntil(null);

        client.post().uri("/api/payments")
                .header("Authorization", "Bearer " + StubJwtDecoderConfig.VALID_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(TRANSFER_BODY)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://revolutclone.com/problems/missing-idempotency-key");

        assertThat(downstream.postCount()).isEqualTo(before);
    }

    private WebTestClient.RequestHeadersSpec<?> post(String idempotencyKey) {
        return client.post().uri("/api/payments")
                .header("Authorization", "Bearer " + StubJwtDecoderConfig.VALID_TOKEN)
                .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(TRANSFER_BODY);
    }

    private static String bodyOf(EntityExchangeResult<byte[]> result) {
        byte[] body = result.getResponseBody();
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }
}
