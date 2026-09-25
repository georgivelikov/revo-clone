package com.revolutclone.ledger.web.error;

import com.revolutclone.ledger.posting.EntryRejectionReason;
import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * {@code PostingEngine.postEntry} signals a business rejection as a {@code PostEntryResult
 * .Rejected} value, not an exception - see that sealed interface's own javadoc: "A rejection is
 * a normal, expected business outcome with a named reason, not an exception." This class exists
 * only to translate that value into the RFC-7807 response at the HTTP boundary, per
 * {@code POST /internal/ledger/entries}'s contract. {@code ACCOUNT_NOT_FOUND} maps to 404 (the
 * caller referenced an account that doesn't exist); every other reason maps to 409 (a
 * well-formed request that conflicts with current ledger state).
 */
public class EntryRejectedException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/entry-rejected");

    public EntryRejectedException(EntryRejectionReason reason, UUID accountId, String idempotencyKey) {
        super(statusFor(reason), TYPE, "Entry rejected",
                "entry " + idempotencyKey + " rejected for account " + accountId + ": " + reason);
    }

    private static HttpStatus statusFor(EntryRejectionReason reason) {
        return reason == EntryRejectionReason.ACCOUNT_NOT_FOUND ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
    }
}
