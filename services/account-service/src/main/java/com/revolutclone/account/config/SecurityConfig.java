package com.revolutclone.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Overrides common-security's default filter chain only to state explicitly that every
 * {@code /api/accounts/**} route requires authentication - there is no unauthenticated route
 * in this service, unlike identity-service's {@code /register}. Every service re-validates the
 * JWT independently of the gateway's edge check; see
 * plans/phase-01-identity-account-gateway.md's {@code ### api-gateway} section.
 *
 * <p>{@code /internal/accounts/vaults} (task 06.6) is the one exception - see
 * {@code InternalVaultAccountController}'s javadoc for why it is deliberately not behind a JWT.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http
                // Stateless JWT resource server: no session/cookie means no CSRF token to
                // steal. Left on, CSRF 403s every non-GET request before authentication even
                // runs. See docs/spring-boot-4-notes.md.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/swagger-ui/**", "/v3/api-docs/**",
                                // See InternalVaultAccountController's javadoc: the caller is another
                                // backend service with no login of its own, and this project has no
                                // service-to-service identity yet (Phase 07).
                                "/internal/accounts/vaults")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}
