package com.revolutclone.card.ledger;

import com.revolutclone.security.api.ServiceAccountTokenProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The HTTP half of {@link LedgerReservations}: {@code POST /internal/ledger/reservations} on
 * ledger-service (task 02.9).
 *
 * <p><strong>Why {@code RestClient} and where its builder comes from.</strong> Boot pre-configures a
 * prototype {@code RestClient.Builder} with the right message converters and request factory, which
 * is what this injects rather than assembling a client by hand. That auto-configuration lives in Boot
 * 4.1's {@code spring-boot-restclient} module, which {@code spring-boot-starter-web} does
 * <em>not</em> pull in - see docs/spring-boot-4-notes.md.
 *
 * <p><strong>Authentication from a Kafka consumer thread.</strong> Unlike fx-service's quoting path,
 * which runs inside an HTTP request and can relay the caller's own token, an authorization is decided
 * from a Kafka consumer: there is no user in the picture at all, so {@link #currentBearerToken()}
 * falls back to {@link ServiceAccountTokenProvider} - a client-credentials token against the realm's
 * existing confidential client - whenever no live JWT is on the thread (task 09.4; this was a known
 * gap since Phase 07 - see docs/spring-boot-4-notes.md and the commit this javadoc paragraph replaced
 * for how every card authorization was permanently 401ing before it existed). The 401/403 handling
 * below is kept regardless: a service account that is ever misconfigured or revoked must still fail
 * loudly into the retry chain rather than a wrong, final, visible-to-the-holder decline.
 */
@Component
public class LedgerReservationsRestClient implements LedgerReservations {

    private static final String HOLD_PATH = "/internal/ledger/reservations";
    private static final String SETTLE_PATH = "/internal/ledger/reservations/{id}/card-settlement";
    private static final String RELEASE_PATH = "/internal/ledger/reservations/{id}/release";
    private static final String EXPIRE_PATH = "/internal/ledger/reservations/{id}/expire";

    private final RestClient http;
    private final ObjectProvider<ServiceAccountTokenProvider> serviceAccountTokens;

    public LedgerReservationsRestClient(RestClient.Builder builder, LedgerClientProperties properties,
            ObjectProvider<ServiceAccountTokenProvider> serviceAccountTokens) {
        this.serviceAccountTokens = serviceAccountTokens;
        this.http = builder
                .baseUrl(properties.baseUrl())
                .requestInterceptor((request, body, execution) -> {
                    currentBearerToken().ifPresent(token -> request.getHeaders().setBearerAuth(token));
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public HeldFunds hold(HoldFundsCommand command) {
        try {
            return http.post()
                    .uri(HOLD_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(command)
                    .retrieve()
                    // The ledger answers 409 for exactly one condition on this endpoint -
                    // InsufficientAvailableBalanceException - which is the one answer the network is
                    // entitled to hear as a decline.
                    .onStatus(status -> status.value() == HttpStatus.CONFLICT.value(),
                            (request, response) -> {
                                throw new LedgerInsufficientFundsException("could not hold "
                                        + command.amountMinor() + " minor units of " + command.currency()
                                        + " on ledger account " + command.ledgerAccountId() + ": "
                                        + bodyOf(response));
                            })
                    // Nobody is authenticated on a consumer thread yet - see the class javadoc. A
                    // missing service identity is a deployment gap, not a refusal of this card.
                    .onStatus(status -> status.value() == HttpStatus.UNAUTHORIZED.value()
                                    || status.value() == HttpStatus.FORBIDDEN.value(),
                            (request, response) -> {
                                throw new LedgerUnavailableException("ledger refused an unauthenticated hold "
                                        + "on account " + command.ledgerAccountId() + " ("
                                        + response.getStatusCode() + "); card-service has no "
                                        + "service-to-service identity yet", null);
                            })
                    .onStatus(HttpStatusCode::is4xxClientError,
                            (request, response) -> {
                                throw new LedgerRejectedHoldException("ledger refused the hold on account "
                                        + command.ledgerAccountId() + ": " + bodyOf(response));
                            })
                    .onStatus(HttpStatusCode::is5xxServerError,
                            (request, response) -> {
                                throw new LedgerUnavailableException("ledger answered "
                                        + response.getStatusCode() + " for a hold on account "
                                        + command.ledgerAccountId() + ": " + bodyOf(response), null);
                            })
                    .body(HeldFunds.class);
        } catch (ResourceAccessException ex) {
            // Connection refused, DNS failure, read timeout: the hold may or may not exist, which is
            // precisely the case the hold's own expiry is designed to clean up - and the case the
            // idempotency key makes safe to retry.
            throw new LedgerUnavailableException(
                    "could not reach ledger-service to hold funds for the authorization: " + ex.getMessage(), ex);
        }
    }

    @Override
    public SettledFunds settle(UUID reservationId, SettleFundsCommand command) {
        try {
            return http.post()
                    .uri(SETTLE_PATH, reservationId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(command)
                    .retrieve()
                    // Same missing service identity the hold path documents, and the same answer:
                    // unknown rather than wrong, so the message is retried instead of a settlement
                    // being recorded that the ledger never posted.
                    .onStatus(LedgerReservationsRestClient::isUnauthenticated,
                            (request, response) -> {
                                throw unauthenticated("settle hold " + reservationId, response);
                            })
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw new LedgerRejectedSettlementException("ledger refused the settlement of "
                                + command.settledMinor() + " minor units against hold " + reservationId
                                + " for networkRef " + command.networkRef() + " ("
                                + response.getStatusCode() + "): " + bodyOf(response));
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new LedgerUnavailableException("ledger answered " + response.getStatusCode()
                                + " settling hold " + reservationId + ": " + bodyOf(response), null);
                    })
                    .body(SettledFunds.class);
        } catch (ResourceAccessException ex) {
            throw new LedgerUnavailableException("could not reach ledger-service to settle hold "
                    + reservationId + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public void release(UUID reservationId) {
        try {
            http.post()
                    .uri(RELEASE_PATH, reservationId)
                    .retrieve()
                    .onStatus(LedgerReservationsRestClient::isUnauthenticated,
                            (request, response) -> {
                                throw unauthenticated("release hold " + reservationId, response);
                            })
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw new LedgerRejectedSettlementException("ledger refused the release of hold "
                                + reservationId + " (" + response.getStatusCode() + "): " + bodyOf(response));
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new LedgerUnavailableException("ledger answered " + response.getStatusCode()
                                + " releasing hold " + reservationId + ": " + bodyOf(response), null);
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException ex) {
            throw new LedgerUnavailableException("could not reach ledger-service to release hold "
                    + reservationId + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public void expire(UUID reservationId) {
        try {
            http.post()
                    .uri(EXPIRE_PATH, reservationId)
                    .retrieve()
                    .onStatus(LedgerReservationsRestClient::isUnauthenticated,
                            (request, response) -> {
                                throw unauthenticated("expire hold " + reservationId, response);
                            })
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw new LedgerRejectedSettlementException("ledger refused the expiry of hold "
                                + reservationId + " (" + response.getStatusCode() + "): " + bodyOf(response));
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new LedgerUnavailableException("ledger answered " + response.getStatusCode()
                                + " expiring hold " + reservationId + ": " + bodyOf(response), null);
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException ex) {
            throw new LedgerUnavailableException("could not reach ledger-service to expire hold "
                    + reservationId + ": " + ex.getMessage(), ex);
        }
    }

    private static boolean isUnauthenticated(HttpStatusCode status) {
        return status.value() == HttpStatus.UNAUTHORIZED.value() || status.value() == HttpStatus.FORBIDDEN.value();
    }

    private static LedgerUnavailableException unauthenticated(String attempt, ClientHttpResponse response) {
        return new LedgerUnavailableException("ledger refused an unauthenticated attempt to " + attempt + " ("
                + statusOf(response) + "); card-service has no service-to-service identity yet", null);
    }

    private static String statusOf(ClientHttpResponse response) {
        try {
            return response.getStatusCode().toString();
        } catch (IOException ex) {
            return "<unreadable status: " + ex.getMessage() + ">";
        }
    }

    private Optional<String> currentBearerToken() {
        Optional<String> liveUserToken = Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(authentication -> authentication.getToken().getTokenValue());
        if (liveUserToken.isPresent()) {
            return liveUserToken;
        }
        // No request thread, no live JWT - exactly the Kafka-consumer case this class's javadoc
        // describes. ServiceAccountTokenProvider is only a bean when configured (see
        // SecurityAutoConfiguration); ifAvailable leaves this Optional empty rather than failing
        // if this service is ever run without revolut.security.service-account.client-id set.
        return Optional.ofNullable(serviceAccountTokens.getIfAvailable()).map(ServiceAccountTokenProvider::token);
    }

    /** The problem detail the ledger sent, for the message - best effort, never why a call fails. */
    private static String bodyOf(ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "<unreadable response body: " + ex.getMessage() + ">";
        }
    }
}
