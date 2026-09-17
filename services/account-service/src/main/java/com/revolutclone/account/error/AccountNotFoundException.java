package com.revolutclone.account.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Also thrown for an account that exists but belongs to a different user - a 404 for someone
 * else's resource, never a 403, so the response never confirms the id exists at all.
 */
public class AccountNotFoundException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/account-not-found");

    public AccountNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, TYPE, "Account not found", "no account " + id + " for this user");
    }
}
