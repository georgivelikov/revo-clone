package com.revolutclone.fx.chaos;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * {@code fx.down}: a deliberate 503, indistinguishable on the wire from a genuine fx-service
 * outage - which is the point. payment-service's {@code FxQuoteRestClient} treats any 5xx the
 * same way regardless of cause, so this is what actually trips the {@code payment -> fx}
 * Resilience4j breaker in the chaos experiment.
 */
public class FxDownChaosException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/fx-chaos-down");

    public FxDownChaosException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, TYPE, "fx-service is down",
                "chaos profile: fx.down is set - every quote request is refused until it is cleared");
    }
}
