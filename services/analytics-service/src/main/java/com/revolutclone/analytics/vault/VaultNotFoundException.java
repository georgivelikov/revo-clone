package com.revolutclone.analytics.vault;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Also thrown for a vault that exists but belongs to a different user - a 404, never a 403, so
 * the response never confirms the id exists at all. Same precedent as account-service's
 * {@code AccountNotFoundException}.
 */
public class VaultNotFoundException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/vault-not-found");

    public VaultNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, TYPE, "Vault not found", "no vault " + id + " for this user");
    }
}
