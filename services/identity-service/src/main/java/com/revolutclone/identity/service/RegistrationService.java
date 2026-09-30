package com.revolutclone.identity.service;

import com.revolutclone.identity.domain.UserProfile;
import com.revolutclone.identity.domain.UserStatus;
import com.revolutclone.identity.error.DuplicateEmailException;
import com.revolutclone.identity.error.IdentityProviderUnavailableException;
import com.revolutclone.identity.web.RegisterRequest;
import com.revolutclone.security.api.IdentityProviderPort;
import com.revolutclone.security.api.NewUser;
import com.revolutclone.security.api.ProviderUser;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;

/**
 * plans/phase-01-identity-account-gateway.md, ### identity-service, the register sequence:
 * validate (the controller's {@code @Valid}) -> provision through the port -> one local
 * transaction ({@link UserProfileWriter}) inserting the profile and appending the outbox
 * event. Steps 1-2 here are NOT wrapped in a Spring transaction - the identity provider is an
 * external system with no participation in one. If the write in step 3 fails after the
 * provider user already exists, that is the exact orphan
 * {@code POST /api/identity/admin/reconcile} ({@link ReconciliationService}) exists to fix.
 *
 * <p>Steps 1-2 are wrapped in the {@code identity-keycloak} circuit breaker -
 * plans/phase-07-resilience-and-scale.md's Circuit breakers table. Its fallback's job is to
 * preserve the "never leave a half-created user" invariant, not just to paper over the outage:
 * if {@link IdentityProviderPort#create} succeeds but a later provisioning call fails (Keycloak
 * going down mid-registration), the provider user is deleted before the failure is reported,
 * so a Keycloak-side account with no password and no role never lingers.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private static final String CUSTOMER_ROLE = "customer";
    private static final String CIRCUIT_BREAKER_NAME = "identity-keycloak";

    private final IdentityProviderPort identityProviderPort;
    private final UserProfileWriter writer;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public RegistrationService(IdentityProviderPort identityProviderPort, UserProfileWriter writer,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.identityProviderPort = identityProviderPort;
        this.writer = writer;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    public UserProfile register(RegisterRequest request) {
        CircuitBreaker breaker = circuitBreakerFactory.create(CIRCUIT_BREAKER_NAME);
        ProviderUser providerUser = breaker.run(
                () -> provisionInKeycloak(request),
                RegistrationService::rethrowBusinessRejectionOrReportUnavailable);

        return writer.persistAndPublish(
                UUID.fromString(providerUser.providerUserId()),
                request.email(),
                request.firstName(),
                request.lastName(),
                request.country().toUpperCase(),
                UserStatus.ACTIVE);
    }

    private ProviderUser provisionInKeycloak(RegisterRequest request) {
        ProviderUser providerUser = createProviderUser(request);
        try {
            identityProviderPort.setPassword(providerUser.providerUserId(), request.password().toCharArray());
            identityProviderPort.assignRole(providerUser.providerUserId(), CUSTOMER_ROLE);
        } catch (RuntimeException setupFailed) {
            deleteQuietly(providerUser.providerUserId());
            throw setupFailed;
        }
        return providerUser;
    }

    private ProviderUser createProviderUser(RegisterRequest request) {
        try {
            return identityProviderPort.create(
                    new NewUser(request.email(), request.firstName(), request.lastName()));
        } catch (IllegalStateException alreadyExists) {
            // Both IdentityProviderPort implementations throw this for a duplicate email (see
            // IdentityProviderPortContractTest) - translate to the stable, service-owned 409.
            // A business rejection, not evidence Keycloak is unhealthy - see
            // rethrowBusinessRejectionOrReportUnavailable below.
            throw new DuplicateEmailException(request.email());
        }
    }

    /** Best-effort: if this also fails, the reconcile endpoint is the backstop for the orphan. */
    private void deleteQuietly(String providerUserId) {
        try {
            identityProviderPort.delete(providerUserId);
        } catch (RuntimeException cleanupFailed) {
            log.warn("could not delete half-created provider user {} after provisioning failed",
                    providerUserId, cleanupFailed);
        }
    }

    private static ProviderUser rethrowBusinessRejectionOrReportUnavailable(Throwable throwable) {
        if (throwable instanceof DuplicateEmailException duplicate) {
            // A legitimate rejection, not an outage - must not be reported as 503.
            throw duplicate;
        }
        throw new IdentityProviderUnavailableException(
                "could not register through the identity provider: " + throwable.getMessage());
    }
}
