package com.revolutclone.payment.fx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The HTTP half of {@link FxQuotes}: {@code POST /internal/fx/quotes} on fx-service, behind a
 * Resilience4j circuit breaker - plans/phase-04-fx-multicurrency.md's Variant section names this
 * exact wrapper: "payment-service calls fx-service over HTTP for a quote... behind a Resilience4j
 * circuit breaker."
 *
 * <p>Authentication mirrors fx-service's own {@code LedgerReservationsRestClient}: the payer's own
 * bearer token, relayed, since neither service has an identity of its own yet (a client-credentials
 * identity for service-to-service calls is a Phase 07 concern).
 *
 * <p>Every failure - a 4xx/5xx from fx-service, a network error, or the circuit itself being open
 * - becomes {@link FxQuoteUnavailableException}. There is nothing to distinguish for the caller:
 * either way, this transfer cannot be quoted right now and must be rejected before anything is
 * written, not retried with stale state.
 */
@Component
public class FxQuoteRestClient implements FxQuotes {

    private static final String QUOTE_PATH = "/internal/fx/quotes";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String CIRCUIT_BREAKER_NAME = "fx-quote";

    private final RestClient http;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public FxQuoteRestClient(RestClient.Builder builder, FxClientProperties properties,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.http = builder
                .baseUrl(properties.baseUrl())
                .requestInterceptor((request, body, execution) -> {
                    currentBearerToken().ifPresent(token -> request.getHeaders().setBearerAuth(token));
                    return execution.execute(request, body);
                })
                .build();
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    @Override
    public FxQuoteHold quote(FxQuoteCommand command) {
        CircuitBreaker breaker = circuitBreakerFactory.create(CIRCUIT_BREAKER_NAME);
        return breaker.run(() -> callFxService(command), throwable -> {
            if (throwable instanceof FxQuoteUnavailableException already) {
                throw already;
            }
            // The circuit is open, or some other framework failure never reached callFxService's
            // own try/catch - either way this is the same outcome from the caller's point of view.
            throw new FxQuoteUnavailableException(
                    "fx-service unavailable for a cross-currency quote (" + throwable.getClass().getSimpleName()
                            + "): " + throwable.getMessage(), throwable);
        });
    }

    private FxQuoteHold callFxService(FxQuoteCommand command) {
        try {
            Response response = http.post()
                    .uri(QUOTE_PATH)
                    .header(IDEMPOTENCY_KEY_HEADER, command.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(command.sellCurrency(), command.buyCurrency(), command.sellMinor(), null,
                            command.recipientUserId()))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError,
                            (request, resp) -> {
                                throw new FxQuoteUnavailableException(
                                        "fx-service refused the quote (" + resp.getStatusCode() + "): "
                                                + bodyOf(resp));
                            })
                    .onStatus(HttpStatusCode::is5xxServerError,
                            (request, resp) -> {
                                throw new FxQuoteUnavailableException(
                                        "fx-service answered " + resp.getStatusCode() + " for a quote: "
                                                + bodyOf(resp));
                            })
                    .body(Response.class);
            return new FxQuoteHold(response.quoteId(), response.reservationId(), response.buyLedgerAccountId(),
                    response.sellCurrency(), response.sellMinor(), response.buyCurrency(), response.poolMidMinor(),
                    response.clientBuyMinor(), response.feeMinor(), response.pnlMinor(), response.expiresAt());
        } catch (ResourceAccessException ex) {
            throw new FxQuoteUnavailableException("could not reach fx-service for a quote: " + ex.getMessage(), ex);
        }
    }

    private static Optional<String> currentBearerToken() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(authentication -> authentication.getToken().getTokenValue());
    }

    private static String bodyOf(ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "<unreadable response body: " + ex.getMessage() + ">";
        }
    }

    /** Mirrors fx-service's {@code InternalCreateQuoteRequest}. */
    private record Request(String sellCurrency, String buyCurrency, Long sellMinor, Long buyMinor,
            UUID recipientUserId) {
    }

    /**
     * Mirrors fx-service's {@code InternalQuoteResponse} field-for-field - deliberately, even
     * though {@code state} is never read here: an unmapped field on the wire is a deserialization
     * error unless every field is declared.
     */
    private record Response(UUID quoteId, UUID reservationId, UUID buyLedgerAccountId, String sellCurrency,
            long sellMinor, String buyCurrency, long poolMidMinor, long clientBuyMinor, long feeMinor,
            long pnlMinor, String state, Instant expiresAt) {
    }
}
