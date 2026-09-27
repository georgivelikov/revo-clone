package com.revolutclone.card.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Same shape as every other service's: a resource server configured with an {@code issuer-uri}
 * fetches that issuer's JWKS at startup, which no test should depend on. Tests authenticate with
 * {@code jwt()} request post-processors, so no token is ever actually decoded.
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
