package com.revolutclone.fx.ledger;

import com.revolutclone.fx.quote.error.InsufficientFundsForQuoteException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * prototype {@code RestClient.Builder} with the right message converters and request factory
 * (<a href="https://docs.spring.io/spring-boot/reference/io/rest-client.html">rest-client.html</a>),
 * which is what this injects rather than assembling a client by hand. That auto-configuration lives
 * in Boot 4.1's {@code spring-boot-restclient} module, which {@code spring-boot-starter-web} does
 * <em>not</em> pull in - see docs/spring-boot-4-notes.md; without the explicit dependency the
 * builder simply is not a bean.
 *
 * <p><strong>Authentication is the caller's own token, relayed.</strong> ledger-service's
 * {@code /internal/**} routes require an authenticated JWT and nothing more (they authorize nothing
 * per-user), and the only token this service has during a quote is the one the user presented. A
 * dedicated client-credentials identity for service-to-service calls would be strictly better and is
 * a Phase 07 concern; relaying is the honest minimum that does not weaken the ledger's own rule that
 * network placement is never the boundary. The relay is a request interceptor rather than a
 * parameter threaded through the domain, so nothing above this class has to know a token exists.
 */
@Component
public class LedgerReservationsRestClient implements LedgerReservations {

    private static final Logger log = LoggerFactory.getLogger(LedgerReservationsRestClient.class);

    private static final String HOLD_PATH = "/internal/ledger/reservations";

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
                    // InsufficientAvailableBalanceException - which is the one outcome a caller of
                    // POST /api/fx/quotes needs to be told about rather than have logged.
                    .onStatus(status -> status.value() == HttpStatus.CONFLICT.value(),
                            (request, response) -> {
                                throw new InsufficientFundsForQuoteException(command.currency(),
                                        command.amountMinor(), bodyOf(response));
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
            // Connection refused, DNS failure, read timeout: the hold may or may not exist, which
            // is precisely the case the hold's own expiry is designed to clean up.
            throw new LedgerUnavailableException(
                    "could not reach ledger-service to hold funds for the quote: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void release(UUID reservationId) {
        try {
            http.post()
                    .uri(HOLD_PATH + "/{id}/release", reservationId)
                    .retrieve()
                    // Compensation runs from a Kafka consumer, where there is no user token to relay
                    // and this service has no identity of its own yet (a client-credentials identity
                    // for service-to-service calls is a Phase 07 concern). An unauthenticated release
                    // is therefore possible and must not become a retry loop: the hold's own 30-second
                    // expiry releases it regardless, which is exactly the backstop the phase designed
                    // it to be - "design every hold with a timeout owned by the resource".
                    .onStatus(status -> status.value() == HttpStatus.UNAUTHORIZED.value()
                                    || status.value() == HttpStatus.FORBIDDEN.value(),
                            (request, response) -> log.warn("ledger refused an unauthenticated release of "
                                    + "reservation {} ({}); leaving it to the hold's own expiry",
                                    reservationId, response.getStatusCode()))
                    // 404 (gone) and 409 (already captured, released or expired) both mean the funds
                    // are not held any more, which is the only thing the caller wanted. Treating them
                    // as failures would turn a compensation that has already happened into a retry
                    // loop against a reservation that will never be HELD again.
                    .onStatus(HttpStatusCode::is4xxClientError,
                            (request, response) -> log.info("ledger will not release reservation {} ({}): "
                                    + "the hold is already gone, which is what this asked for",
                                    reservationId, response.getStatusCode()))
                    .onStatus(HttpStatusCode::is5xxServerError,
                            (request, response) -> {
                                throw new LedgerUnavailableException("ledger answered "
                                        + response.getStatusCode() + " releasing reservation "
                                        + reservationId + ": " + bodyOf(response), null);
                            })
                    .toBodilessEntity();
        } catch (ResourceAccessException ex) {
            throw new LedgerUnavailableException(
                    "could not reach ledger-service to release reservation " + reservationId + ": "
                            + ex.getMessage(), ex);
        }
    }

    private static Optional<String> currentBearerToken() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(authentication -> authentication.getToken().getTokenValue());
    }

    /** The problem detail the ledger sent, for the log - best effort, never the reason a call fails. */
    private static String bodyOf(ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "<unreadable response body: " + ex.getMessage() + ">";
        }
    }
}
