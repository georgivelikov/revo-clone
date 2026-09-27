package com.revolutclone.fx.quote.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The caller has no wallet in the buy currency for the exchange to be paid into. The mirror of
 * {@link SellWalletNotFoundException}, and 409 for the same reason: a precondition on the caller's
 * own accounts rather than a missing resource.
 *
 * <p>Raised at execution rather than at quote time, because that is when the credit side actually
 * needs an account: quoting only has to hold the sell side. The quote is left {@code ACTIVE} and
 * its funds held - the caller can open the wallet and execute the same quote while it lasts, and
 * the hold's own expiry cleans up if they do not.
 */
public class BuyWalletNotFoundException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/fx-buy-wallet-not-found");

    public BuyWalletNotFoundException(String buyCurrency) {
        super(HttpStatus.CONFLICT, TYPE, "No wallet to buy into",
                "no " + buyCurrency + " wallet is known for this user to receive the exchange");
    }
}
