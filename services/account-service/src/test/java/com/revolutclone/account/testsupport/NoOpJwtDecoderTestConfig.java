package com.revolutclone.account.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * None of this service's tests present a real signed JWT to the resource-server filter - they
 * authenticate via {@code SecurityMockMvcRequestPostProcessors.jwt()}, which injects an
 * already-authenticated token straight into the security context and never calls a
 * {@link JwtDecoder}. Supplying this no-op bean pre-empts Boot's own issuer-uri-based
 * auto-configured decoder, which otherwise fetches OIDC discovery metadata from
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} at context startup - not worth
 * pointing at a real reachable value just to satisfy a decoder these tests never invoke. Same
 * pattern as identity-service's own copy of this class (phase 01 task 2).
 */
@TestConfiguration
public class NoOpJwtDecoderTestConfig {

    @Bean
    JwtDecoder jwtDecoder() {
        return token -> {
            throw new UnsupportedOperationException(
                    "not used in tests - authenticate with SecurityMockMvcRequestPostProcessors.jwt() instead");
        };
    }
}
