package com.revolutclone.fx.ledger;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The ledger could not be reached, or answered with a server error, so whether a hold exists is
 * unknown. 503 rather than 500: the request was well formed and retrying it later is the right
 * advice.
 *
 * <p>An unknown hold is safe to abandon, which is the whole reason the hold carries its own
 * expiry - see {@link LedgerReservations}. fx-service does not attempt to release it, because it
 * cannot know a reservation id it never received.
 */
public class LedgerUnavailableException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/ledger-unavailable");

    public LedgerUnavailableException(String detail, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, TYPE, "Ledger unavailable", detail);
        initCause(cause);
    }
}
