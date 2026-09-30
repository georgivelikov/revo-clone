package com.revolutclone.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Overrides common-security's default filter chain to state explicitly that every
 * {@code /api/payments/**} route requires authentication. The gateway authenticates too, but
 * each service re-validates the JWT independently rather than trusting network placement as
 * the boundary - same precedent as identity-service, account-service and ledger-service. It
 * matters more here than anywhere: the authenticated subject is what task 03.3 checks the
 * source account against, so "cannot send from an account you do not own" rests on this chain
 * having actually run.
 *
 * <p>{@code /internal/payments} (task 06.6) is the one exception - see
 * {@code InternalPaymentController}'s javadoc for why it is deliberately not behind a JWT.
 *
 * <p>{@code /admin/chaos/**} (task 07.6, {@code common-messaging}'s
 * {@code ChaosOutboxAdminController}) is the other: {@code simulator-service} calls it with no
 * bearer token, same precedent as {@code /internal/payments}. Safe only because that controller
 * is itself {@code @Profile("chaos")}-gated and never registered outside that profile.
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
                // runs - which would be every POST /api/payments. See
                // docs/spring-boot-4-notes.md.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/swagger-ui/**", "/v3/api-docs/**",
                                // See InternalPaymentController's javadoc: the caller is another
                                // backend service with no login of its own, and this project has no
                                // service-to-service identity yet (Phase 07).
                                "/internal/payments", "/admin/chaos/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}
