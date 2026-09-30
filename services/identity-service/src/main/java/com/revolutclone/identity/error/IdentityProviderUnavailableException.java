package com.revolutclone.identity.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The Keycloak Admin API call registration depends on failed, or the {@code identity-keycloak}
 * circuit breaker is already open from a prior failure - plans/phase-07-resilience-and-scale.md's
 * Circuit breakers table: "Fail registration fast with a clear message; never leave a
 * half-created user." 503 rather than 500: this is a known, named dependency outage, not an
 * unexpected internal error, and a client can reasonably retry once the provider recovers.
 */
public class IdentityProviderUnavailableException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/identity-provider-unavailable");

    public IdentityProviderUnavailableException(String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, TYPE, "Identity provider unavailable", detail);
    }
}
