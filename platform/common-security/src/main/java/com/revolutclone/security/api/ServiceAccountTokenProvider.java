package com.revolutclone.security.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A client-credentials bearer token for the one case {@code @CurrentUser}'s live JWT cannot
 * cover: a synchronous outbound call made from a Kafka consumer thread, where there is no HTTP
 * request and so no caller token to relay. {@code card-service}'s
 * {@code LedgerReservationsRestClient} is the first caller - see its own javadoc on why an
 * authorization decided off a Kafka event has no user in the picture at all.
 *
 * <p><strong>Not a replacement for a live user JWT.</strong> A request-thread caller already has
 * the right token - its own - and must keep using that; this exists only for a thread with none.
 * The realm's existing confidential {@code revolut-backend} client (already used by
 * {@code KeycloakIdentityAdapter}'s admin calls) is reused rather than minting a new per-service
 * client: a client-credentials grant against it yields a plain realm-issued JWT with no special
 * realm-management grants attached, which is all {@code .anyRequest().authenticated()} on the
 * receiving service needs - it carries no {@code realm_access.roles} claim, so it can never
 * satisfy a {@code hasAuthority(...)} check, only bare authentication.
 *
 * <p>Caches the token and refreshes it a safety margin before {@code expires_in} lapses, so a
 * burst of authorizations shares one token rather than hitting Keycloak's token endpoint per
 * call. Synchronized rather than a lock-free CAS: token refresh is rare (minutes apart) and
 * blocking a few concurrent callers for one HTTP round trip is cheap next to that.
 */
public class ServiceAccountTokenProvider {

    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper jsonMapper;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;

    private volatile CachedToken cached;

    public ServiceAccountTokenProvider(JsonMapper jsonMapper, String tokenUri, String clientId, String clientSecret) {
        this.jsonMapper = jsonMapper;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public synchronized String token() {
        CachedToken current = cached;
        if (current == null || Instant.now().isAfter(current.refreshAt())) {
            cached = fetch();
        }
        return cached.value();
    }

    private CachedToken fetch() {
        String body = "grant_type=client_credentials&client_id=" + clientId + "&client_secret=" + clientSecret;
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new ServiceAccountTokenException("could not reach " + tokenUri + " for a service-account token", ex);
        }
        if (response.statusCode() != 200) {
            throw new ServiceAccountTokenException(
                    tokenUri + " answered " + response.statusCode() + " for a client-credentials grant: "
                            + response.body());
        }
        // Read as a tree and pick fields by their literal snake_case names, the same idiom
        // EventTypeRouter already uses: Keycloak's token endpoint answers access_token/
        // expires_in, and mapping that onto a camelCase record needs either a property-rename
        // annotation (see docs/spring-boot-4-notes.md's note on this - its import is real but
        // easily flagged as a mistake by a naive check) or a naming-strategy module on the
        // shared JsonMapper bean just for this one caller. A tree read needs neither.
        JsonNode node = jsonMapper.readTree(response.body());
        String accessToken = node.get("access_token").asString();
        long expiresIn = node.get("expires_in").asLong();
        Instant expiresAt = Instant.now().plusSeconds(expiresIn);
        return new CachedToken(accessToken, expiresAt.minus(REFRESH_MARGIN));
    }

    private record CachedToken(String value, Instant refreshAt) {
    }
}
