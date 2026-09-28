package com.revolutclone.ledger.reservation;

import java.util.UUID;

/**
 * What a card settlement did, split into the four figures a caller cannot re-derive on its own
 * because only the ledger knows what was actually held.
 *
 * @param captureEntryId         the {@code CARD_SETTLEMENT} journal entry that captured the hold
 * @param capturedMinor          how much of the hold was captured - the whole hold on an exact or an
 *                               over-settlement, less than it on a partial
 * @param releasedRemainderMinor what went back to {@code available} because the settlement came in
 *                               under the authorization; zero otherwise
 * @param excessMinor            how much was settled <em>beyond</em> the hold - a tip, a fuel
 *                               top-up, a hotel incidental. Zero on every other case, and the only
 *                               figure here that can have pushed a wallet negative
 * @param excessEntryId          the second journal entry that posted {@code excessMinor}, or null
 *                               when there was no excess. Two entries rather than one because the
 *                               excess is not a capture of anything: no hold covers it, so it is an
 *                               ordinary debit and is accounted for as one
 */
public record CardSettlementResult(
        UUID captureEntryId,
        long capturedMinor,
        long releasedRemainderMinor,
        long excessMinor,
        UUID excessEntryId) {

    /** True when the merchant captured more than it authorized - the path that may overdraw. */
    public boolean isOverSettlement() {
        return excessMinor > 0;
    }
}
