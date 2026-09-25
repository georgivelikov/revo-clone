package com.revolutclone.ledger.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Same pattern as account-service's/identity-service's own copy of this class: MockMvc tests
 * here authenticate via {@code SecurityMockMvcRequestPostProcessors.jwt()}, which injects an
 * already-authenticated token straight into the security context and never calls a
 * {@link JwtDecoder} - this no-op bean pre-empts Boot's issuer-uri-based auto-configured
 * decoder, which would otherwise try to fetch OIDC discovery metadata at context startup.
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
