package com.revolutclone.analytics.payment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The HTTP half of {@link PaymentInitiationClient}: {@code POST /internal/payments} on
 * payment-service (task 06.6). No auth relay is needed - that endpoint is deliberately not
 * behind a JWT (see its own javadoc), so there is nothing for this client to attach.
 *
 * <p>Every 4xx becomes {@link PaymentRejectedException} (payment-service's own validation
 * decided this transfer is unacceptable); every 5xx or an unreachable host becomes
 * {@link PaymentServiceUnavailableException}. Neither is silently swallowed.
 */
@Component
public class PaymentsRestClient implements PaymentInitiationClient {

    private static final String PATH = "/internal/payments";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final RestClient http;

    public PaymentsRestClient(RestClient.Builder builder, PaymentClientProperties properties) {
        this.http = builder.baseUrl(properties.baseUrl()).build();
    }

    @Override
    public InitiatedPayment initiate(InitiatePaymentCommand command) {
        try {
            Response response = http.post()
                    .uri(PATH)
                    .header(IDEMPOTENCY_KEY_HEADER, command.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(command.userId(), command.sourceAccountId(), command.targetAccountId(),
                            command.amountMinor(), command.currency(), command.reference()))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError,
                            (request, resp) -> {
                                throw new PaymentRejectedException(
                                        "payment-service refused the transfer (" + resp.getStatusCode() + "): "
                                                + bodyOf(resp));
                            })
                    .onStatus(HttpStatusCode::is5xxServerError,
                            (request, resp) -> {
                                throw new PaymentServiceUnavailableException(
                                        "payment-service answered " + resp.getStatusCode()
                                                + " initiating a transfer: " + bodyOf(resp));
                            })
                    .body(Response.class);
            return new InitiatedPayment(response.paymentId(), response.status());
        } catch (ResourceAccessException ex) {
            throw new PaymentServiceUnavailableException(
                    "could not reach payment-service to initiate a transfer: " + ex.getMessage());
        }
    }

    private static String bodyOf(ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "<unreadable response body: " + ex.getMessage() + ">";
        }
    }

    /** Mirrors payment-service's {@code InternalCreatePaymentRequest}. */
    private record Request(UUID userId, UUID sourceAccountId, UUID targetAccountId, long amountMinor,
            String currency, String reference) {
    }

    /**
     * Mirrors payment-service's {@code PaymentResponse} field-for-field, even though only
     * {@code paymentId}/{@code status} are read here - an unmapped field on the wire is a
     * deserialization error unless every field is declared (Jackson 3's default, see
     * payment-service's own {@code FxQuoteRestClient} for the identical note).
     */
    private record Response(UUID paymentId, String status, UUID sourceAccountId, UUID targetAccountId,
            long amountMinor, String currency, String targetCurrency, Long targetAmountMinor, String reference,
            String failureReason, UUID ledgerEntryId, Instant createdAt, Instant updatedAt) {
    }
}
