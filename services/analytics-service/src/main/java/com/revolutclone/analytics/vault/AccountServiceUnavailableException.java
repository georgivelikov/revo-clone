package com.revolutclone.analytics.vault;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * account-service could not be reached, or answered with a server error, while opening a vault's
 * backing account. 503, not 500: this is account-service's problem, not the caller's, and
 * retrying shortly is the correct response - same precedent as card-service's
 * {@code WalletCurrencyUnknownException}.
 */
public class AccountServiceUnavailableException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/account-service-unavailable");

    public AccountServiceUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, TYPE, "Account service unavailable", message);
    }
}
