package com.revolutclone.card.ledger;

import java.util.UUID;

/**
 * What the ledger did with a settlement, as it answered. A subset of the ledger's own
 * {@code CardSettlementResponse}.
 *
 * <p>{@code capturedMinor} and {@code releasedRemainderMinor} are read back rather than assumed
 * because the split was made against the hold, and the hold is the ledger's fact: this service asked
 * for a settlement amount, not for a particular division of it.
 *
 * @param captureEntryId         the {@code CARD_SETTLEMENT} entry - the first posting this
 *                               authorization has ever had, and what makes it settled rather than
 *                               pending
 * @param capturedMinor          how much of the hold was captured
 * @param releasedRemainderMinor what went back to the holder's available balance, on a partial
 * @param excessMinor            how much was settled beyond the hold; positive only on an
 *                               over-settlement, and the figure that gets flagged
 * @param excessEntryId          the second entry, which posted {@code excessMinor}; null otherwise
 */
public record SettledFunds(
        UUID captureEntryId,
        long capturedMinor,
        long releasedRemainderMinor,
        long excessMinor,
        UUID excessEntryId) {
}
