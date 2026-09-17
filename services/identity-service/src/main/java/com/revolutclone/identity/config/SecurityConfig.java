package com.revolutclone.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Overrides common-security's default filter chain (which requires authentication for
 * everything except actuator/OpenAPI) to also permit
 * {@code POST /api/identity/register} - the one unauthenticated route in the whole system, per
 * plans/phase-01-identity-account-gateway.md's {@code ### api-gateway} section - and to
 * restrict {@code /api/identity/admin/**} to the {@code admin} authority.
 * {@code ConfigurableRolesClaimConverter} issues unprefixed authorities (e.g. {@code admin}),
 * not Spring's default {@code ROLE_}-prefixed ones, so authorization here uses
 * {@code hasAuthority}, never {@code hasRole}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http
                // Stateless JWT resource server, same reasoning as common-security's default
                // filter chain (see SecurityAutoConfiguration): no session/cookie means no
                // CSRF token to steal, and leaving CSRF on rejects every non-GET request -
                // including the one unauthenticated route below - with 403.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers("/api/identity/register").permitAll()
                        .requestMatchers("/api/identity/admin/**").hasAuthority("admin")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}
