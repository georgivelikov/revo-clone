package com.revolutclone.fx.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Overrides common-security's default filter chain to state explicitly that every
 * {@code /api/fx/**} route requires authentication - same precedent as identity-service,
 * account-service, ledger-service and payment-service. The first such route is
 * {@code POST /api/fx/quotes}, which reserves the caller's funds and therefore identifies them
 * from the token's subject rather than from anything in the request body.
 *
 * <p>{@code /admin/chaos/**} (task 07.6, {@code ChaosFxAdminController} and
 * {@code common-messaging}'s {@code ChaosOutboxAdminController}) is the one exception, same
 * precedent as {@code card-service}'s {@code /internal/cards/*&#47;pan-for-network}:
 * {@code simulator-service} calls it with no bearer token. Safe only because those controllers
 * are themselves {@code @Profile("chaos")}-gated and never registered outside that profile.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http
                // Stateless JWT resource server: no session/cookie means no CSRF token to
                // steal. See docs/spring-boot-4-notes.md.
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
