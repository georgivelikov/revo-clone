package com.revolutclone.gateway.security;

import com.revolutclone.security.RevolutSecurityProperties;
import com.revolutclone.security.api.ConfigurableRolesClaimConverter;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * Edge JWT validation, WebFlux-native. Reuses {@link ConfigurableRolesClaimConverter} (no
 * Servlet types, so it works unchanged here) wrapped in the standard, synchronous
 * {@link JwtAuthenticationConverter} and bridged into the reactive stack with
 * {@link ReactiveJwtAuthenticationConverterAdapter} - both are plain Spring Security classes,
 * not this project's own code, so no duplication was needed to get the roles-claim mapping
 * working on WebFlux.
 *
 * <p>This is edge validation only - a fast reject, not the system's trust boundary. Each
 * downstream service re-validates the same JWT independently. Spring Cloud Gateway's default
 * behaviour of forwarding the incoming {@code Authorization} header unchanged to the routed
 * service is what the phase spec calls "TokenRelay" here; the gateway {@code TokenRelay}
 * {@code GatewayFilter} that ships with {@code spring-boot-starter-oauth2-client} is a
 * different mechanism (relaying a token the gateway itself obtained via {@code oauth2Login})
 * that does not fit this project's bearer-token API shape, where the SPA authenticates
 * directly against Keycloak and always presents its own {@code Authorization} header - so it
 * is deliberately not used here.
 *
 * <p>CORS is configured in exactly this one place per the phase spec; no other service may
 * add it.
 */
@Configuration
@EnableConfigurationProperties(RevolutSecurityProperties.class)
public class GatewaySecurityConfig {

    private final RevolutSecurityProperties securityProperties;
    private final String allowedOrigin;

    public GatewaySecurityConfig(RevolutSecurityProperties securityProperties,
                                  @org.springframework.beans.factory.annotation.Value("${revolut.gateway.cors.allowed-origin:http://localhost:5173}") String allowedOrigin) {
        this.securityProperties = securityProperties;
        this.allowedOrigin = allowedOrigin;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/api/identity/register").permitAll()
                        .pathMatchers("/actuator/health/**").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(reactiveJwtAuthenticationConverter())));
        return http.build();
    }

    private Converter<Jwt, Mono<AbstractAuthenticationToken>> reactiveJwtAuthenticationConverter() {
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(new ConfigurableRolesClaimConverter(securityProperties.rolesClaim()));
        return new ReactiveJwtAuthenticationConverterAdapter(delegate);
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
