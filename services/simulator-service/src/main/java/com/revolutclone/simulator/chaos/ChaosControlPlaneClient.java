package com.revolutclone.simulator.chaos;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The HTTP half of the chaos control plane (plans/phase-07-resilience-and-scale.md's Chaos
 * control plane): {@code ledger.*} and {@code fx.*} levers cannot be applied from here directly -
 * this service has no way to reach into another JVM's memory - so this forwards each one to the
 * {@code @Profile("chaos")} admin endpoint the target service itself exposes
 * ({@code ChaosLedgerAdminController}, {@code ChaosFxAdminController},
 * {@code common-messaging}'s {@code ChaosOutboxAdminController}). No bearer token, same as
 * {@code CardNetworkPanRestClient}: this project has no service-to-service identity yet, and
 * those admin endpoints are reachable unauthenticated for exactly that reason (see their own
 * javadoc).
 */
@Component
public class ChaosControlPlaneClient {

    private final RestClient http;
    private final ChaosSimulatorProperties properties;

    public ChaosControlPlaneClient(RestClient.Builder builder, ChaosSimulatorProperties properties) {
        this.http = builder.build();
        this.properties = properties;
    }

    public void setLedgerDelay(long delayMs) {
        postLedger(new LedgerChaosRequest(delayMs, null, null));
    }

    public void setLedgerFailRatePercent(int failRatePercent) {
        postLedger(new LedgerChaosRequest(null, failRatePercent, null));
    }

    public void setLedgerRejectAll(boolean rejectAll) {
        postLedger(new LedgerChaosRequest(null, null, rejectAll));
    }

    public void resetLedger() {
        post(properties.ledgerBaseUrl() + "/admin/chaos/ledger/reset", null);
    }

    public void setFxDown(boolean down) {
        post(properties.fxBaseUrl() + "/admin/chaos/fx", new FxChaosRequest(down));
    }

    public void pauseOutbox(String service) {
        post(outboxBaseUrl(service) + "/admin/chaos/outbox/pause", null);
    }

    public void resumeOutbox(String service) {
        post(outboxBaseUrl(service) + "/admin/chaos/outbox/resume", null);
    }

    private void postLedger(LedgerChaosRequest body) {
        post(properties.ledgerBaseUrl() + "/admin/chaos/ledger", body);
    }

    private String outboxBaseUrl(String service) {
        return switch (service) {
            case "payment-service" -> properties.paymentBaseUrl();
            case "ledger-service" -> properties.ledgerBaseUrl();
            default -> throw new InvalidChaosCommandException(
                    "outbox.pause/outbox.resume only know \"payment-service\" and \"ledger-service\", got \""
                            + service + "\"");
        };
    }

    private void post(String uri, Object body) {
        try {
            RestClient.RequestBodySpec spec = http.post().uri(uri);
            RestClient.ResponseSpec response = body != null
                    ? spec.contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                    : spec.retrieve();
            response.toBodilessEntity();
        } catch (RestClientException ex) {
            throw new ChaosTargetUnavailableException(
                    "could not reach " + uri + " (" + ex.getClass().getSimpleName() + "): " + ex.getMessage());
        }
    }

    private record LedgerChaosRequest(Long delayMs, Integer failRatePercent, Boolean rejectAll) {
    }

    private record FxChaosRequest(boolean down) {
    }
}
