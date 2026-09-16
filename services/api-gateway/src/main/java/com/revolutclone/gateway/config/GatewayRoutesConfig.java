package com.revolutclone.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * The routes themselves live in {@code application.yml} under
 * {@code spring.cloud.gateway.server.webflux.routes}, not here - the {@code RequestRateLimiter}
 * filter has no Java DSL. {@code GatewayFilterSpec.requestRateLimiter(...)} compiles and looks
 * right, but every request through it throws {@code IllegalArgumentException: No Configuration
 * found for route <id> or defaultFilters} at request time, because the Java builder path never
 * registers the route's {@code Config} into {@code RedisRateLimiter}'s internal map the way the
 * YAML filter-factory path does. Confirmed against
 * docs.spring.io's own reference, which shows no Java DSL example for this filter and says so
 * outright - see docs/spring-boot-4-notes.md. This class only supplies the
 * {@link KeyResolver} bean that the YAML routes reference via {@code #{@userKeyResolver}}.
 */
@Configuration
class GatewayRoutesConfig {

    /**
     * Keys the rate limiter on the JWT subject so the limit is per user, not per connection.
     * Requests that reach a route without an authenticated principal (only
     * {@code /api/identity/register} can, per the security config) fall back to a shared
     * "anonymous" bucket.
     */
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .cast(JwtAuthenticationToken.class)
                .map(auth -> auth.getToken().getSubject())
                .switchIfEmpty(Mono.just("anonymous"));
    }
}
