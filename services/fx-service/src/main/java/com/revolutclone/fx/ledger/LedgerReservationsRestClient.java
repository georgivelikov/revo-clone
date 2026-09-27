package com.revolutclone.fx.ledger;

import com.revolutclone.fx.quote.error.InsufficientFundsForQuoteException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
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
