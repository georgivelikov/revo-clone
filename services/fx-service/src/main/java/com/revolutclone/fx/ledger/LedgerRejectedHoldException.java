package com.revolutclone.fx.ledger;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The ledger refused the hold request itself - a malformed request, an unknown ledger account, a
 * currency that does not match the account. Every one of those means fx-service's own projections or
 * request building are wrong, not that the caller did anything invalid, so this surfaces as a 500
 * and carries the ledger's own problem detail into the log rather than pretending it was a client
 * error.
 */
public class LedgerRejectedHoldException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/ledger-rejected-hold");

    public LedgerRejectedHoldException(String detail) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, TYPE, "Ledger rejected the hold", detail);
    }
}
