package com.revolutclone.fx.quote.error;

import com.revolutclone.fx.quote.FxQuoteState;
import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The quote cannot be executed: it ran out of time, or it already ended some other way.
 *
 * <p>409 with the state named, because the two cases the UI has to tell apart both land here.
 * plans/phase-04-fx-multicurrency.md is specific about the expired one - "on expiry the confirm
 * button disables and offers a refresh - do not silently re-quote at a new rate, which is how real
 * apps get accused of sharp practice" - so this must be a refusal the client can see, never a
 * quiet re-quote at whatever the rate has moved to.
 *
 * <p>Expiry is always decided against the server clock. A client's countdown ring is presentation
 * only; a clock a few seconds slow would otherwise be able to execute a rate that has already
 * lapsed.
 */
public class QuoteNotExecutableException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/fx-quote-not-executable");

    private QuoteNotExecutableException(String detail) {
        super(HttpStatus.CONFLICT, TYPE, "Quote cannot be executed", detail);
    }

    public static QuoteNotExecutableException expired(UUID quoteId) {
        return new QuoteNotExecutableException("quote " + quoteId
                + " expired - take a new quote rather than executing this rate");
    }

    public static QuoteNotExecutableException inState(UUID quoteId, FxQuoteState state) {
        return new QuoteNotExecutableException("quote " + quoteId + " is " + state + ", not ACTIVE");
    }
}
