package com.revolutclone.identity.service;

import com.revolutclone.identity.domain.UserProfile;
import com.revolutclone.identity.domain.UserStatus;
import com.revolutclone.identity.error.DuplicateEmailException;
import com.revolutclone.identity.web.RegisterRequest;
import com.revolutclone.security.api.IdentityProviderPort;
import com.revolutclone.security.api.NewUser;
import com.revolutclone.security.api.ProviderUser;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * plans/phase-01-identity-account-gateway.md, ### identity-service, the register sequence:
 * validate (the controller's {@code @Valid}) -> provision through the port -> one local
 * transaction ({@link UserProfileWriter}) inserting the profile and appending the outbox
 * event. Steps 1-2 here are NOT wrapped in a Spring transaction - the identity provider is an
 * external system with no participation in one. If the write in step 3 fails after the
 * provider user already exists, that is the exact orphan
 * {@code POST /api/identity/admin/reconcile} ({@link ReconciliationService}) exists to fix.
 */
@Service
public class RegistrationService {

    private static final String CUSTOMER_ROLE = "customer";

    private final IdentityProviderPort identityProviderPort;
    private final UserProfileWriter writer;

    public RegistrationService(IdentityProviderPort identityProviderPort, UserProfileWriter writer) {
        this.identityProviderPort = identityProviderPort;
        this.writer = writer;
    }

    public UserProfile register(RegisterRequest request) {
        ProviderUser providerUser = createProviderUser(request);

        identityProviderPort.setPassword(providerUser.providerUserId(), request.password().toCharArray());
        identityProviderPort.assignRole(providerUser.providerUserId(), CUSTOMER_ROLE);

        return writer.persistAndPublish(
                UUID.fromString(providerUser.providerUserId()),
                request.email(),
                request.firstName(),
                request.lastName(),
                request.country().toUpperCase(),
                UserStatus.ACTIVE);
    }

    private ProviderUser createProviderUser(RegisterRequest request) {
        try {
            return identityProviderPort.create(
                    new NewUser(request.email(), request.firstName(), request.lastName()));
        } catch (IllegalStateException alreadyExists) {
            // Both IdentityProviderPort implementations throw this for a duplicate email (see
            // IdentityProviderPortContractTest) - translate to the stable, service-owned 409.
            throw new DuplicateEmailException(request.email());
        }
    }
}
