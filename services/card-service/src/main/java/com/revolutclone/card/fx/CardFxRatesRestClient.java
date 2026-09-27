package com.revolutclone.card.fx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The HTTP half of {@link CardFxRates}: {@code GET /internal/fx/rates/{sell}/{buy}} on fx-service.
 *
 * <p><strong>Every failure is the same failure.</strong> Unlike the ledger hold, where a 409 means
 * "insufficient funds" and is the one answer the network may hear as a decline, nothing fx-service
 * can say about a rate is a statement about this card. A 503 from a cache still warming, a 401
 * because a Kafka consumer thread carries no token, a connection refused - all of them mean "we
 * cannot price this purchase yet", all of them are retried, and none of them refuses a good card.
 * That is why this maps the whole status range onto one exception rather than discriminating.
 *
 * <p><strong>The missing service identity is the same known gap the ledger client documents.</strong>
 * An authorization is decided from a Kafka consumer, so there is no user in the picture and this
 * project has no client-credentials identity for service-to-service calls yet (a Phase 07 concern).
 * The interceptor below therefore finds a token only when something drives this from a request
 * thread. Until then fx-service answers 401 and the message is retried - loudly wrong rather than
 * quietly declining somebody's holiday.
 *
 * <p>{@code RestClient.Builder} is injected rather than assembled, and its autoconfiguration lives in
 * Boot 4.1's own {@code spring-boot-restclient} module which {@code spring-boot-starter-web} does not
 * pull in - see docs/spring-boot-4-notes.md.
 */
@Component
public class CardFxRatesRestClient implements CardFxRates {

    private static final String RATE_PATH = "/internal/fx/rates/{sellCurrency}/{buyCurrency}";

    private final RestClient http;

    public CardFxRatesRestClient(RestClient.Builder builder, CardFxProperties properties) {
        this.http = builder
                .baseUrl(properties.baseUrl())
                .requestInterceptor((request, body, execution) -> {
                    currentBearerToken().ifPresent(token -> request.getHeaders().setBearerAuth(token));
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public CardFxRate rate(String walletCurrency, String merchantCurrency) {
        try {
            // The wallet sells and the merchant's currency is bought - the direction fx-service applies
            // its spread in. See CardFxPricer on why the pair is this way round.
            InternalRate rate = http.get()
                    .uri(RATE_PATH, walletCurrency, merchantCurrency)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (request, response) -> {
                                throw new CardFxRateUnavailableException("fx-service answered "
                                        + response.getStatusCode() + " for " + walletCurrency + "/"
                                        + merchantCurrency + ": " + bodyOf(response));
                            })
                    .body(InternalRate.class);

            if (rate == null || rate.exchangeRate() == null || rate.midRate() == null) {
                throw new CardFxRateUnavailableException(
                        "fx-service returned no rate for " + walletCurrency + "/" + merchantCurrency);
            }
            return new CardFxRate(walletCurrency, merchantCurrency, rate.midRate(), rate.exchangeRate(),
                    rate.spreadBps());
        } catch (ResourceAccessException ex) {
            throw new CardFxRateUnavailableException("could not reach fx-service for the "
                    + walletCurrency + "/" + merchantCurrency + " rate: " + ex.getMessage(), ex);
        }
    }

    /**
     * fx-service's {@code InternalRateResponse} as seen from here. Its own type deliberately:
     * card-service must not depend on fx-service's module (CLAUDE.md rule 7's spirit - a shared DTO
     * class is a shared schema), and {@code clientRate} is renamed to {@code exchangeRate} on the way
     * in because "the client rate" means something different on this side of the wire, where the card
     * mark-up is still to come.
     */
    record InternalRate(String sellCurrency, String buyCurrency, String midRate,
            String clientRate, int spreadBps) {

        String exchangeRate() {
            return clientRate;
        }
    }

    private static Optional<String> currentBearerToken() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(authentication -> authentication.getToken().getTokenValue());
    }

    /** The problem detail fx-service sent, for the message - best effort, never why a call fails. */
    private static String bodyOf(ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "<unreadable response body: " + ex.getMessage() + ">";
        }
    }
}
