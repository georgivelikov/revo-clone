package com.revolutclone.gateway.idempotency;

import com.revolutclone.gateway.correlation.CorrelationWebFilter;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Writes an RFC-7807 problem response from the gateway, matching the shape {@code common-web}'s
 * {@code ApiExceptionHandler} produces in every business service - same {@code type} URI base,
 * same {@code correlationId} extension - so a client sees one error format across the whole API.
 *
 * <p>The JSON is assembled by hand rather than through a mapper because {@code common-web} is
 * deliberately not on this WebFlux-only module's classpath (it is Servlet-shaped; see
 * {@link CorrelationWebFilter}), and because the gateway must be able to answer a rejected
 * money-moving POST without depending on anything that could itself fail. That is safe only
 * while every {@code title}/{@code detail} passed in is a compile-time constant free of quotes
 * and backslashes, which is why they live as constants in {@link IdempotencyFilter} rather than
 * being built from request data.
 */
final class ProblemResponse {

    private static final String TYPE_BASE = "https://revolutclone.com/problems/";

    private ProblemResponse() {
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String typeSlug,
                            String title, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        StringBuilder json = new StringBuilder(256)
                .append("{\"type\":\"").append(TYPE_BASE).append(typeSlug)
                .append("\",\"title\":\"").append(title)
                .append("\",\"status\":").append(status.value())
                .append(",\"detail\":\"").append(detail).append('"');
        String correlationId = response.getHeaders().getFirst(CorrelationWebFilter.HEADER);
        if (correlationId != null) {
            json.append(",\"correlationId\":\"").append(correlationId).append('"');
        }
        json.append('}');

        DataBuffer buffer = response.bufferFactory().wrap(json.toString().getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
