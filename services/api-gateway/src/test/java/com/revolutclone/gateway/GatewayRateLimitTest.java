package com.revolutclone.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * A burst past the configured per-user limit returns 429. Uses a real Redis (Testcontainers,
 * same {@code redis:7-alpine} image as {@code docker-compose.infra.yml}) rather than mocking
 * the rate limiter, since the whole point of {@code RedisRateLimiter} is its Lua-script
 * token-bucket behaviour against a real Redis. The limiter is configured down to
 * replenish-rate=1/burst-capacity=1 for this test only, via
 * {@code revolut.gateway.rate-limiter.*} - the route-level {@code RequestRateLimiter} filter's
 * args in {@code application.yml} reference those properties, which is what makes them
 * overridable per-test (the filter itself has no Java DSL and must be configured via YAML
 * {@code args}, not a bean; see docs/spring-boot-4-notes.md). A two-request burst
 * deterministically exhausts the bucket. The unauthenticated {@code /api/identity/register}
 * route is used so the test does not also depend on the JWT stub from {@link GatewayAuthTest};
 * unauthenticated requests share one "anonymous" rate-limiter key (see
 * {@code GatewayRoutesConfig.userKeyResolver}), which is exactly what this test needs.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(StubJwtDecoderConfig.class)
// See GatewayAuthTest's javadoc note on why this is @TestPropertySource, not a
// src/test/resources/application.yml (which would shadow, not merge with, main's).
@TestPropertySource(properties = {
        "revolut.gateway.rate-limiter.replenish-rate=1",
        "revolut.gateway.rate-limiter.burst-capacity=1",
        "OIDC_ISSUER_URI=https://example.invalid/realms/test",
        "CORS_ALLOWED_ORIGIN=http://localhost:5173"
})
class GatewayRateLimitTest {

    @Container
    @ServiceConnection("redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static HttpServer stubDownstream;

    @Autowired
    private WebTestClient client;

    @BeforeAll
    static void startStubDownstream() throws Exception {
        stubDownstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubDownstream.createContext("/", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stubDownstream.start();
    }

    @AfterAll
    static void stopStubDownstream() {
        stubDownstream.stop(0);
    }

    @DynamicPropertySource
    static void routeToStub(DynamicPropertyRegistry registry) {
        registry.add("revolut.gateway.routes.identity-service-uri",
                () -> "http://127.0.0.1:" + stubDownstream.getAddress().getPort());
        registry.add("revolut.gateway.routes.account-service-uri",
                () -> "http://127.0.0.1:" + stubDownstream.getAddress().getPort());
        registry.add("revolut.gateway.routes.payment-service-uri",
                () -> "http://127.0.0.1:" + stubDownstream.getAddress().getPort());
        registry.add("revolut.gateway.routes.ledger-service-uri",
                () -> "http://127.0.0.1:" + stubDownstream.getAddress().getPort());
        registry.add("revolut.gateway.routes.notification-service-uri",
                () -> "http://127.0.0.1:" + stubDownstream.getAddress().getPort());
        registry.add("revolut.gateway.routes.fx-service-uri",
                () -> "http://127.0.0.1:" + stubDownstream.getAddress().getPort());
        registry.add("revolut.gateway.routes.card-service-uri",
                () -> "http://127.0.0.1:" + stubDownstream.getAddress().getPort());
        registry.add("revolut.gateway.routes.analytics-service-uri",
                () -> "http://127.0.0.1:" + stubDownstream.getAddress().getPort());
    }

    @Test
    void burstPastTheLimitReturns429() {
        // First request consumes the single available token.
        client.get().uri("/api/identity/register")
                .exchange()
                .expectStatus().isOk();

        // The bucket is empty and refills at 1/second; an immediate second request must be
        // rejected rather than silently allowed through.
        client.get().uri("/api/identity/register")
                .exchange()
                .expectStatus().isEqualTo(429);
    }
}
