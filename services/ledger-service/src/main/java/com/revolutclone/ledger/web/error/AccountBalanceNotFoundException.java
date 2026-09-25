package com.revolutclone.ledger.web.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** No {@code ledger_account} (and therefore no {@code account_balance}) with this id. */
public class AccountBalanceNotFoundException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/ledger-account-not-found");

    public AccountBalanceNotFoundException(UUID ledgerAccountId) {
        super(HttpStatus.NOT_FOUND, TYPE, "Ledger account not found",
                "no ledger_account with id " + ledgerAccountId);
    }
}
