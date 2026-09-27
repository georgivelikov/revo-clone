package com.revolutclone.fx.quote.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * No quote with this id belongs to the caller.
 *
 * <p>Deliberately the same answer for "no such quote" and "someone else's quote": a quote id is a
 * bare UUID, and a 403 on one that exists while a 404 comes back for one that does not would turn
 * this endpoint into an oracle for which ids are real. Nothing about another user's quote is
 * useful to this caller, so nothing about it is disclosed.
 */
public class QuoteNotFoundException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/fx-quote-not-found");

    public QuoteNotFoundException(UUID quoteId) {
        super(HttpStatus.NOT_FOUND, TYPE, "Quote not found", "no quote " + quoteId + " for this user");
    }
}
