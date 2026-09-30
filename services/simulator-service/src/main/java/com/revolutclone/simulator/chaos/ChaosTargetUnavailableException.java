package com.revolutclone.simulator.chaos;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/** The service a lever was forwarded to (ledger-service, fx-service, ...) could not be reached. */
public class ChaosTargetUnavailableException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/chaos-target-unavailable");

    public ChaosTargetUnavailableException(String detail) {
        super(HttpStatus.BAD_GATEWAY, TYPE, "Chaos target unavailable", detail);
    }
}
