package com.revolutclone.security;

import com.revolutclone.security.api.ConfigurableRolesClaimConverter;
import com.revolutclone.security.api.CurrentUserArgumentResolver;
import com.revolutclone.security.api.IdentityProviderPort;
import com.revolutclone.security.api.InMemoryIdentityAdapter;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the resource-server JWT validation, the configurable roles-claim converter and
 * {@link CurrentUserArgumentResolver} into any service that depends on this module. The
 * lambda DSL below is unchanged between Spring Security 6 and 7 - see
 * docs/spring-boot-4-notes.md.
 *
 * <p>{@link IdentityProviderPort} itself is not bound here: {@code common-security-keycloak}
 * (this module's {@code keycloak/} package) supplies {@code KeycloakIdentityAdapter} when
 * {@code revolut.identity.provider=keycloak}, and {@link InMemoryIdentityAdapter} below is the
 * fallback whenever no other {@link IdentityProviderPort} bean is present.
 */
@AutoConfiguration
@EnableWebSecurity
@EnableConfigurationProperties(RevolutSecurityProperties.class)
public class SecurityAutoConfiguration implements WebMvcConfigurer {

    private final RevolutSecurityProperties properties;

    public SecurityAutoConfiguration(RevolutSecurityProperties properties) {
        this.properties = properties;
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigurableRolesClaimConverter configurableRolesClaimConverter() {
        return new ConfigurableRolesClaimConverter(properties.rolesClaim());
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationConverter jwtAuthenticationConverter(
            ConfigurableRolesClaimConverter rolesClaimConverter) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(rolesClaimConverter);
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                // Every service here is a stateless JWT resource server - no session, no
                // cookie, so no CSRF token to steal. CSRF protection defends session/cookie
                // auth; leaving it on rejects every non-GET request from a bearer-token
                // client with 403, including unauthenticated ones like a register endpoint.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdentityProviderPort inMemoryIdentityAdapter() {
        return new InMemoryIdentityAdapter();
    }

    @Bean
    public CurrentUserArgumentResolver currentUserArgumentResolver() {
        return new CurrentUserArgumentResolver();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver());
    }
}
