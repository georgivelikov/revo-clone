package com.revolutclone.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * Stands in for a real Keycloak issuer in gateway tests. Providing this bean makes Spring
 * Security's resource-server auto-configuration back off from trying to reach
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} at all (it is
 * {@code @ConditionalOnMissingBean(ReactiveJwtDecoder.class)}), so these tests never need a
 * live Keycloak - important because {@code mvn clean verify} must pass from a cold checkout
 * with no manually-started stack, and {@code agent/gate.ps1} runs exactly that.
 */
@TestConfiguration
public class StubJwtDecoderConfig {

    public static final String VALID_TOKEN = "valid-test-token";

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder() {
        return token -> {
            if (!VALID_TOKEN.equals(token)) {
                return Mono.error(new BadJwtException("bad token"));
            }
            Instant now = Instant.now();
            Jwt jwt = Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "user-123")
                    .claim("realm_access", Map.of("roles", List.of("customer")))
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(300))
                    .build();
            return Mono.just(jwt);
        };
    }
}
