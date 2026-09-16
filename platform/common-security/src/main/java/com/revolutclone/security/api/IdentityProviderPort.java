package com.revolutclone.security.api;

import java.util.List;
import java.util.Optional;

/**
 * The seam over user provisioning, so identity-service never calls a Keycloak Admin API
 * directly. Keycloak is the only provider planned - this exists to keep vendor calls out of
 * business code, not in anticipation of a migration. {@link InMemoryIdentityAdapter} is the
 * second implementation, used in tests where a Keycloak Testcontainer would be too slow.
 */
public interface IdentityProviderPort {

    /** @return the created user, including the provider-assigned id. */
    ProviderUser create(NewUser user);

    void setPassword(String providerUserId, char[] password);

    void assignRole(String providerUserId, String role);

    Optional<ProviderUser> findByEmail(String email);

    /** The compensation path: undo a partially-completed registration saga. */
    void delete(String providerUserId);

    /** For the reconcile endpoint (phase 01). */
    List<ProviderUser> listAll(int page, int size);
}
