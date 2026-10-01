package com.revolutclone.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Overrides common-security's default chain for one reason: <strong>the WebSocket handshake at
 * {@code /ws} has to be permitted anonymously.</strong> A browser cannot set an
 * {@code Authorization} header on a WebSocket handshake, so there is no bearer token for the
 * resource server to validate - the handshake is authenticated instead by
 * {@code TicketHandshakeInterceptor}, which redeems a single-use Redis ticket and refuses the
 * upgrade if there is not one. Everything else, including the endpoint that issues those tickets,
 * needs a valid JWT as usual.
 *
 * <p>Note what this does <em>not</em> mean: {@code permitAll} on {@code /ws} opens the URL, not the
 * data. The interceptor runs inside the handshake, before any socket exists, and no event is ever
 * published to a session without a principal resolved from a redeemed ticket.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The permitted path is read from {@link NotificationProperties}, not spelled out again here:
     * {@code WS_PATH} configures where the endpoint is registered, and a matcher that disagreed with
     * it would either lock the handshake out or leave a hole at the old path.
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter,
            NotificationProperties properties) throws Exception {
        String websocketPath = properties.websocket().path();
        http
                // Stateless JWT resource server: no session, no cookie, so nothing for CSRF to
                // protect - and left on it 403s every POST before authentication runs, including
                // the ticket endpoint. See docs/spring-boot-4-notes.md.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(websocketPath, websocketPath + "/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}
