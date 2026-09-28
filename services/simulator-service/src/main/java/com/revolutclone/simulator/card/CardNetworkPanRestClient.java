package com.revolutclone.simulator.card;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The HTTP half of {@link CardNetworkPanClient}: {@code GET /internal/cards/{cardId}/pan-for-
 * network} on card-service (task 05.8). Carries no bearer token, unlike this project's other
 * internal REST clients - that endpoint is deliberately unauthenticated; see
 * {@code NetworkPanController}'s javadoc on card-service for why.
 */
@Component
public class CardNetworkPanRestClient implements CardNetworkPanClient {

    private static final String PATH = "/internal/cards/{cardId}/pan-for-network";

    private final RestClient http;

    public CardNetworkPanRestClient(RestClient.Builder builder, CardSimulatorProperties properties) {
        this.http = builder.baseUrl(properties.baseUrl()).build();
    }

    @Override
    public String panFor(UUID cardId) {
        try {
            NetworkPanResponse response = http.get()
                    .uri(PATH, cardId)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (request, resp) -> {
                        throw new UnknownCardException(cardId);
                    })
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (request, resp) -> {
                                throw new CardNetworkUnavailableException("card-service answered "
                                        + resp.getStatusCode() + " looking up card " + cardId + ": "
                                        + bodyOf(resp));
                            })
                    .body(NetworkPanResponse.class);

            if (response == null || response.pan() == null) {
                throw new CardNetworkUnavailableException(
                        "card-service returned no PAN for card " + cardId);
            }
            return response.pan();
        } catch (ResourceAccessException ex) {
            throw new CardNetworkUnavailableException(
                    "could not reach card-service to look up card " + cardId + ": " + ex.getMessage());
        }
    }

    private static String bodyOf(ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "<unreadable response body: " + ex.getMessage() + ">";
        }
    }

    /** card-service's {@code NetworkPanResponse} as seen from here - this service's own type. */
    record NetworkPanResponse(String pan) {
    }
}
