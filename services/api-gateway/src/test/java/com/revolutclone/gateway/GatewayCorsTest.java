package com.revolutclone.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The browser may be opened on {@code localhost} or {@code 127.0.0.1}; Keycloak accepts both, so
 * the gateway must too, or login succeeds and the first API call dies on CORS. A comma-separated
 * {@code CORS_ALLOWED_ORIGIN} lists every allowed origin; anything else gets no CORS headers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(StubJwtDecoderConfig.class)
@TestPropertySource(properties = {
        "REDIS_HOST=127.0.0.1",
        "OIDC_ISSUER_URI=https://example.invalid/realms/test",
        "CORS_ALLOWED_ORIGIN=http://localhost:5173,http://127.0.0.1:5173",
        // Route URIs are required to bind; a CORS preflight never reaches a downstream.
        "revolut.gateway.routes.identity-service-uri=http://127.0.0.1:9",
        "revolut.gateway.routes.account-service-uri=http://127.0.0.1:9",
        "revolut.gateway.routes.payment-service-uri=http://127.0.0.1:9",
        "revolut.gateway.routes.ledger-service-uri=http://127.0.0.1:9",
        "revolut.gateway.routes.notification-service-uri=http://127.0.0.1:9",
        "revolut.gateway.routes.fx-service-uri=http://127.0.0.1:9",
        "revolut.gateway.routes.card-service-uri=http://127.0.0.1:9",
        "revolut.gateway.routes.analytics-service-uri=http://127.0.0.1:9"
})
class GatewayCorsTest {

    @Autowired
    private WebTestClient client;

    @Test
    void preflightIsAllowedFromEveryListedOrigin() {
        for (String origin : new String[] {"http://localhost:5173", "http://127.0.0.1:5173"}) {
            client.options().uri("/api/accounts/me")
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "authorization")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals("Access-Control-Allow-Origin", origin);
        }
    }

    @Test
    void preflightFromAnUnlistedOriginIsRejected() {
        client.options().uri("/api/accounts/me")
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isForbidden();
    }
}
