package com.revolutclone.card.web.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * A limit was asked to be set, but {@code funding_account} has no row yet for the card's
 * {@code account_id} - the same projection lag {@code FundingWalletResolver}'s javadoc describes
 * for the authorization path. Not this holder's fault and not a permanent state, so 503 rather
 * than 404 or 400: retrying shortly is the correct response, exactly as it is for the
 * authorization chain hitting the identical gap.
 */
public class WalletCurrencyUnknownException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/wallet-currency-unknown");

    public WalletCurrencyUnknownException(UUID accountId) {
        super(HttpStatus.SERVICE_UNAVAILABLE, TYPE, "Wallet currency unknown",
                "No funding wallet projection yet for account " + accountId + "; try again shortly.");
    }
}
