package com.revolutclone.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Overrides common-security's default filter chain to state explicitly that every
 * {@code /internal/ledger/**} route requires authentication - per
 * plans/phase-02-ledger.md's {@code ### API} section, these are service-to-service only and
 * are not routed by the gateway, but each service still re-validates the JWT independently
 * rather than trusting network placement as the boundary (same precedent as
 * identity-service/account-service; see plans/phase-01-identity-account-gateway.md's
 * {@code ### api-gateway} section).
 *
 * <p>{@code /admin/chaos/**} (task 07.6, {@code ChaosLedgerAdminController} and
 * {@code common-messaging}'s {@code ChaosOutboxAdminController}) is the one exception, same
 * precedent as {@code card-service}'s {@code /internal/cards/*&#47;pan-for-network}:
 * {@code simulator-service} calls it with no bearer token, since this project has no
 * service-to-service identity yet. Safe only because those controllers are themselves
 * {@code @Profile("chaos")}-gated and never registered outside that profile.
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
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/swagger-ui/**", "/v3/api-docs/**",
                                "/admin/chaos/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}
