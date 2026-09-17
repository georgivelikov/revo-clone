package com.revolutclone.gateway.correlation;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The WebFlux-native equivalent of {@code common-web}'s {@code CorrelationIdFilter}: that one
 * is a Servlet {@code OncePerRequestFilter} and cannot run on this reactive-only gateway (see
 * {@link com.revolutclone.gateway.GatewayApplication}'s javadoc for why the module doesn't
 * even put {@code common-web} on the classpath). Same behaviour, reactive shape: read
 * {@code X-Correlation-Id} or mint one, echo it on the response, forward it unchanged to the
 * routed service by rewriting the request so downstream always sees the header, and put it in
 * MDC for this gateway's own structured logs.
 *
 * <p>MDC is thread-local and Reactor's execution can hop threads within one request's
 * pipeline; {@code MDC.put}/{@code remove} around this filter's own synchronous body still
 * correctly tags any log line emitted synchronously within the filter (route matching,
 * security decisions logged at this layer), which is what this project's structured logging
 * needs at the edge. Full MDC continuity across every downstream reactive hop would need
 * Reactor {@code Context} propagation into a logging bridge, which is unnecessary here since
 * each business service re-validates and re-logs the request under its own correlation id.
 *
 * <p>{@code doFinally} logs exactly one line per request, with MDC set around just that call -
 * found live (task 01.7) that without it, MDC held {@code correlationId} but nothing ever
 * actually emitted a log line carrying it, on either this gateway or a routed service whose
 * handler logs nothing on a plain request. Silently defeats CLAUDE.md rule 10 otherwise.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationWebFilter.class);

    public static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String correlationId = request.getHeaders().getFirst(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        String finalCorrelationId = correlationId;

        ServerHttpRequest mutatedRequest = request.mutate()
                .header(HEADER, finalCorrelationId)
                .build();
        exchange.getResponse().getHeaders().set(HEADER, finalCorrelationId);

        MDC.put(MDC_KEY, finalCorrelationId);
        try {
            return chain.filter(exchange.mutate().request(mutatedRequest).build())
                    .contextWrite(ctx -> ctx.put(MDC_KEY, finalCorrelationId))
                    .doFinally(signal -> {
                        MDC.put(MDC_KEY, finalCorrelationId);
                        try {
                            log.info("{} {} -> {}", request.getMethod(), request.getURI().getPath(),
                                    exchange.getResponse().getStatusCode());
                        } finally {
                            MDC.remove(MDC_KEY);
                        }
                    });
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
