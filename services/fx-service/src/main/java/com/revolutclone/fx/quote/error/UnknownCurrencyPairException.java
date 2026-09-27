package com.revolutclone.fx.quote.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * No mid rate is known for this pair - either it is not one of the supported pairs, or no tick has
 * arrived yet on a freshly started instance whose cache is still warming from the topic tail.
 *
 * <p>503 rather than 400, because the two cases are indistinguishable from here and the second one
 * resolves itself within a tick or two: telling a client "you asked for something invalid" when the
 * real answer is "ask again in half a second" would be a lie the UI could not recover from.
 */
public class UnknownCurrencyPairException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/fx-rate-unavailable");

    public UnknownCurrencyPairException(String sellCurrency, String buyCurrency) {
        super(HttpStatus.SERVICE_UNAVAILABLE, TYPE, "No rate available",
                "no mid rate is currently known for " + sellCurrency + "/" + buyCurrency);
    }
}
