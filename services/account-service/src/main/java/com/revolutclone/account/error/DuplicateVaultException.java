package com.revolutclone.account.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The caller already has a VAULT account with this name in this currency -
 * {@code unique(user_id, currency, type, name)}. Task 06.6's counterpart to
 * {@link DuplicateWalletException}: a wallet is unique per currency alone (its name is always
 * {@code ""}), a vault is unique per (currency, name) since a user may want several vaults in the
 * same currency.
 */
public class DuplicateVaultException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/duplicate-vault");

    public DuplicateVaultException(String currency, String name) {
        super(HttpStatus.CONFLICT, TYPE, "Duplicate vault",
                "a VAULT account named '" + name + "' in " + currency + " already exists for this user");
    }
}
