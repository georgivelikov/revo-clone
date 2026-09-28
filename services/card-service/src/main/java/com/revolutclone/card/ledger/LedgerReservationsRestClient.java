package com.revolutclone.card.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
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
 * <p><strong>Authentication is a known gap, and it is deliberate that it fails loudly rather than
 * quietly.</strong> Unlike fx-service's quoting path, which runs inside an HTTP request and can relay
 * the caller's own token, an authorization is decided from a Kafka consumer: there is no user in the
 * picture at all, and this project has no client-credentials identity for service-to-service calls
 * yet (a Phase 07 concern, same note fx-service's release path carries). The relay below therefore
 * finds a token only when something drives this from a request thread. Until a service identity
 * exists, the ledger answers 401 to a hold from the consumer, and this maps that to
 * {@link LedgerUnavailableException} - the message is retried, and nobody is told their good card was
 * declined. Treating it as a decline would be the worst of the available options: wrong, final, and
 * visible to the holder.
 */
@Component
public class LedgerReservationsRestClient implements LedgerReservations {

    private static final String HOLD_PATH = "/internal/ledger/reservations";
    private static final String SETTLE_PATH = "/internal/ledger/reservations/{id}/card-settlement";
    private static final String RELEASE_PATH = "/internal/ledger/reservations/{id}/release";
    private static final String EXPIRE_PATH = "/internal/ledger/reservations/{id}/expire";

    private final RestClient http;

    public LedgerReservationsRestClient(RestClient.Builder builder, LedgerClientProperties properties) {
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

    private static Optional<String> currentBearerToken() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(authentication -> authentication.getToken().getTokenValue());
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
