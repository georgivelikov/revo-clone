package com.revolutclone.analytics.vault;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * account-service already has a VAULT account with this name and currency for this user -
 * account-service's own {@code unique(user_id, currency, type, name)}, mapped from its 409.
 */
public class DuplicateVaultAccountException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/duplicate-vault-account");

    public DuplicateVaultAccountException(String name, String currency) {
        super(HttpStatus.CONFLICT, TYPE, "Duplicate vault",
                "a vault named '" + name + "' in " + currency + " already exists for this user");
    }
}
