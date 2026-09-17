package com.revolutclone.ledger.posting;

import com.revolutclone.ledger.domain.PostingDirection;
import java.util.UUID;

/**
 * One leg of a {@link PostEntryCommand}. {@code reservationId} is set only when this line
 * captures an existing hold - see {@link PostingEngine}'s class-level comment for the
 * balance-column-semantics table this drives. Per that table, only a {@code DEBIT} may carry
 * a {@code reservationId}; there is no defined meaning for a {@code CREDIT} capture.
 */
public record PostingLine(UUID ledgerAccountId, PostingDirection direction, long amountMinor, String currency,
        UUID reservationId) {

    public PostingLine {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be > 0, got " + amountMinor);
        }
        if (reservationId != null && direction != PostingDirection.DEBIT) {
            throw new IllegalArgumentException(
                    "reservationId may only be set on a DEBIT posting (a capture), got " + direction);
        }
    }
}
