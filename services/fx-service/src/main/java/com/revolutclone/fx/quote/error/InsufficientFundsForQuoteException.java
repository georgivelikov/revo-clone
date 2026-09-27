package com.revolutclone.fx.quote.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The ledger would not hold the sell amount: the caller's available balance does not cover it.
 *
 * <p>This is the visible consequence of quoting by reserving rather than by locking a rate, and the
 * outcome {@code QuoteReservesFundsTest} drives - two quotes against one balance, where the second
 * one fails here rather than being issued against funds the first one already claimed.
 *
 * <p>409 rather than 402/422, mirroring the ledger's own answer for the same condition: the request
 * is perfectly valid and will succeed once the existing hold is released or executed, so it is a
 * conflict with current state rather than a malformed request.
 */
public class InsufficientFundsForQuoteException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/fx-insufficient-available-funds");

    public InsufficientFundsForQuoteException(String currency, long requestedMinor, String ledgerDetail) {
        super(HttpStatus.CONFLICT, TYPE, "Insufficient available funds",
                "could not hold " + requestedMinor + " minor units of " + currency
                        + " for this quote - funds already reserved or spent: " + ledgerDetail);
    }
}
