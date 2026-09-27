package com.revolutclone.fx.quote.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The caller has no wallet in the sell currency that fx-service can see. Either they genuinely hold
 * no such wallet, or this instance's projections have not yet caught up with one opened moments ago -
 * see {@code WalletResolver} for why those two look identical from here.
 *
 * <p>409, not 404: the missing thing is not the quote resource being addressed, it is a precondition
 * on the caller's own accounts.
 */
public class SellWalletNotFoundException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/fx-sell-wallet-not-found");

    public SellWalletNotFoundException(String sellCurrency) {
        super(HttpStatus.CONFLICT, TYPE, "No wallet to sell from",
                "no " + sellCurrency + " wallet is known for this user");
    }
}
