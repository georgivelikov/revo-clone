package com.revolutclone.analytics.vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The HTTP half of {@link VaultAccountOpener}: {@code POST /internal/accounts/vaults} on
 * account-service (task 06.6). No auth relay is needed, unlike card-service's
 * {@code LedgerReservationsRestClient} - that endpoint is deliberately not behind a JWT (see its
 * own javadoc), so there is nothing for this client to attach.
 *
 * <p>Every failure account-service can hand back is turned into an explicit, typed outcome
 * rather than swallowed: a 409 (the name/currency is already taken) becomes
 * {@link DuplicateVaultAccountException}; any other 4xx, a 5xx, or an unreachable host all
 * become {@link AccountServiceUnavailableException} - account-service's own bug or downtime,
 * never this caller's fault.
 */
@Component
public class AccountsRestClient implements VaultAccountOpener {

    private static final String PATH = "/internal/accounts/vaults";

    private final RestClient http;

    public AccountsRestClient(RestClient.Builder builder, AccountClientProperties properties) {
        this.http = builder.baseUrl(properties.baseUrl()).build();
    }

    @Override
    public UUID open(UUID userId, String currency, String name) {
        try {
            Response response = http.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(userId, currency, name))
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.CONFLICT.value(),
                            (request, resp) -> {
                                throw new DuplicateVaultAccountException(name, currency);
                            })
                    .onStatus(HttpStatusCode::is4xxClientError,
                            (request, resp) -> {
                                throw new AccountServiceUnavailableException(
                                        "account-service refused to open a vault account ("
                                                + resp.getStatusCode() + "): " + bodyOf(resp));
                            })
                    .onStatus(HttpStatusCode::is5xxServerError,
                            (request, resp) -> {
                                throw new AccountServiceUnavailableException(
                                        "account-service answered " + resp.getStatusCode()
                                                + " opening a vault account: " + bodyOf(resp));
                            })
                    .body(Response.class);
            return response.id();
        } catch (ResourceAccessException ex) {
            throw new AccountServiceUnavailableException(
                    "could not reach account-service to open a vault account: " + ex.getMessage());
        }
    }

    private static String bodyOf(ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "<unreadable response body: " + ex.getMessage() + ">";
        }
    }

    /** Mirrors account-service's {@code OpenVaultAccountRequest}. */
    private record Request(UUID userId, String currency, String name) {
    }

    /** Mirrors account-service's {@code AccountResponse} field-for-field; only {@code id} is read here. */
    private record Response(UUID id, UUID userId, String currency, String type, String name, String state,
            UUID ledgerAccountId, Long balance, Instant createdAt) {
    }
}
