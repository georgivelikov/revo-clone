package com.revolutclone.ledger.reservation.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * A hold was requested for more than {@code available - reserved} on a {@code USER_WALLET} or
 * {@code VAULT} account - the same spendable check {@code PostingTransactionExecutor} applies to
 * a plain debit, applied here before any money moves to {@code reserved}.
 */
public class InsufficientAvailableBalanceException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/insufficient-available-balance");

    public InsufficientAvailableBalanceException(UUID ledgerAccountId, long requestedMinor, long spendableMinor) {
        super(HttpStatus.CONFLICT, TYPE, "Insufficient available balance",
                "account " + ledgerAccountId + " has " + spendableMinor + " spendable minor units, requested "
                        + requestedMinor);
    }
}
