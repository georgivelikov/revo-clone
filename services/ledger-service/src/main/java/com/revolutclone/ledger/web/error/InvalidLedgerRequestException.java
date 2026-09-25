package com.revolutclone.ledger.web.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * Wraps an {@link IllegalArgumentException} thrown by {@code PostingEngine}/
 * {@code ReservationService} for a malformed request (e.g. an idempotency key that doesn't
 * match its {@code JournalEntryType}'s required prefix, an entry with fewer than two postings)
 * as a proper 400 {@code ProblemDetail} rather than letting it fall through to
 * {@code ApiExceptionHandler}'s catch-all 500 - these are client input errors, not server
 * failures. The domain layer's own {@code IllegalArgumentException} checks stay as the
 * authoritative backstop (they also protect any future non-HTTP caller); this class only
 * translates the HTTP-boundary presentation.
 */
public class InvalidLedgerRequestException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/invalid-ledger-request");

    public InvalidLedgerRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, TYPE, "Invalid request", detail);
    }
}
