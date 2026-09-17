package com.revolutclone.account.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/** The caller already has a WALLET in this currency - unique(user_id, currency, type, name). */
public class DuplicateWalletException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/duplicate-wallet");

    public DuplicateWalletException(String currency) {
        super(HttpStatus.CONFLICT, TYPE, "Duplicate wallet",
                "a WALLET account in " + currency + " already exists for this user");
    }
}
