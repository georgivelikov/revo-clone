package com.revolutclone.analytics.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Same shape as every other service's: a resource server configured with an {@code issuer-uri}
 * fetches that issuer's JWKS at startup, which no test should depend on. No test in this module
 * authenticates an HTTP request yet (task 06.1 exposes none), but the resource-server
 * autoconfiguration still runs, so a real network fetch would otherwise be attempted.
 */
@TestConfiguration
public class NoOpJwtDecoderTestConfig {

    @Bean
    JwtDecoder jwtDecoder() {
        return token -> {
            throw new UnsupportedOperationException("no test should decode a real token");
        };
    }
}
