package com.revolutclone.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * No token -> 401; a token this gateway's edge validation accepts -> the request is
 * authenticated, routed, and the stub downstream returns 200.
 *
 * <p>What this test deliberately does NOT prove, because downstream services (identity-service,
 * account-service) don't exist until later Phase 01 tasks: that a real account-service ever
 * rejects one user reading another's accounts. That is a downstream authorization concern, not
 * an edge-authentication one - the gateway only proves the JWT is valid and forwards it
 * unchanged; who owns what is checked by the service that owns the data, in
 * {@code AccountQueryTest} once {@code account-service} exists. Here, a stub JDK
 * {@code HttpServer} stands in for both downstream services so the route/security/proxy chain
 * is exercised for real rather than mocked at the Spring layer, and {@link StubJwtDecoderConfig}
 * stands in for Keycloak so the test needs no live IdP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(StubJwtDecoderConfig.class)
// A src/test/resources/application.yml is deliberately NOT used here: Spring Boot resolves
// the single classpath:/application.yml resource, and a same-named file under test resources
// entirely REPLACES src/main/resources/application.yml for tests rather than merging with it
// - which silently dropped this module's spring.cloud.gateway routes the first time this was
// tried (see docs/spring-boot-4-notes.md). @TestPropertySource only adds/overrides individual
// keys on top of the real application.yml, which is what's needed here.
@TestPropertySource(properties = {
        "REDIS_HOST=127.0.0.1",
        "OIDC_ISSUER_URI=https://example.invalid/realms/test",
        "CORS_ALLOWED_ORIGIN=http://localhost:5173"
})
class GatewayAuthTest {

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
    }

    @Test
    void noTokenIsRejected() {
        client.get().uri("/api/accounts/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void validTokenIsAuthenticatedAndRouted() {
        client.get().uri("/api/accounts/me")
                .header("Authorization", "Bearer " + StubJwtDecoderConfig.VALID_TOKEN)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void registerRouteNeedsNoToken() {
        client.get().uri("/api/identity/register")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void wrongTokenIsRejected() {
        client.get().uri("/api/accounts/me")
                .header("Authorization", "Bearer garbage")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
