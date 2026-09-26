package com.revolutclone.gateway.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.gateway.StubJwtDecoderConfig;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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
 * plans/phase-03-payments-and-ui.md, test table: same {@code Idempotency-Key}, different body ->
 * "returns 422, does not replay the old response".
 *
 * <p>Both halves matter. 422 rather than a silent replay because a key promises "this is the same
 * request I sent before", and a client that breaks that promise has a bug - replaying the first
 * response would hide it and, worse, tell the client that a €20 transfer it never made was
 * accepted. And the request must not reach payment-service either: a second, different transfer
 * under a key that is already spoken for is not a transfer anyone asked for.
 *
 * <p>The third assertion is the one that is easy to get wrong: the rejection must leave the
 * original marker intact, so the genuine retry that follows still replays the original response
 * rather than being treated as new work.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(StubJwtDecoderConfig.class)
@TestPropertySource(properties = {
        "OIDC_ISSUER_URI=https://example.invalid/realms/test",
        "CORS_ALLOWED_ORIGIN=http://localhost:5173"
})
class SameKeyDifferentBodyTest {

    private static final String TEN_EUROS = """
            {"sourceAccountId":"11111111-1111-1111-1111-111111111111",\
            "targetAccountId":"22222222-2222-2222-2222-222222222222",\
            "amountMinor":1000,"currency":"EUR","reference":"rent"}""";

    private static final String TWENTY_EUROS = """
            {"sourceAccountId":"11111111-1111-1111-1111-111111111111",\
            "targetAccountId":"22222222-2222-2222-2222-222222222222",\
            "amountMinor":2000,"currency":"EUR","reference":"rent"}""";

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
    }

    @Test
    void sameKeyWithADifferentBodyIs422AndNotAReplay() {
        String key = UUID.randomUUID().toString();

        String firstResponse = bodyOf(post(key, TEN_EUROS).exchange()
                .expectStatus().isAccepted()
                .expectBody().returnResult());

        EntityExchangeResult<byte[]> rejected = post(key, TWENTY_EUROS).exchange()
                .expectStatus().isEqualTo(422)
                .expectHeader().doesNotExist(IdempotencyFilter.REPLAYED_HEADER)
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://revolutclone.com/problems/idempotency-key-reused")
                .returnResult();

        assertThat(bodyOf(rejected))
                .as("the 422 must be a problem detail, not the first request's response replayed")
                .isNotEqualTo(firstResponse)
                .doesNotContain("paymentId");

        assertThat(downstream.postCount())
                .as("the different-bodied request must never reach payment-service")
                .isEqualTo(1);
        assertThat(downstream.receivedBodies())
                .as("only the first body was ever forwarded")
                .containsExactly(TEN_EUROS);

        // The rejection must not have consumed or overwritten the marker: an honest retry of the
        // original request still gets the original answer.
        assertThat(bodyOf(post(key, TEN_EUROS).exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals(IdempotencyFilter.REPLAYED_HEADER, "true")
                .expectBody().returnResult()))
                .isEqualTo(firstResponse);
        assertThat(downstream.postCount()).isEqualTo(1);
    }

    private WebTestClient.RequestHeadersSpec<?> post(String idempotencyKey, String body) {
        return client.post().uri("/api/payments")
                .header("Authorization", "Bearer " + StubJwtDecoderConfig.VALID_TOKEN)
                .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body);
    }

    private static String bodyOf(EntityExchangeResult<byte[]> result) {
        byte[] body = result.getResponseBody();
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }
}
