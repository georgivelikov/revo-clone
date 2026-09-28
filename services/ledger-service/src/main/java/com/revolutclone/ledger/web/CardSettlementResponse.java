package com.revolutclone.ledger.web;

import com.revolutclone.ledger.reservation.CardSettlementResult;
import java.util.UUID;

/** The wire form of {@link CardSettlementResult}. */
public record CardSettlementResponse(
        UUID captureEntryId,
        long capturedMinor,
        long releasedRemainderMinor,
        long excessMinor,
        UUID excessEntryId) {

    static CardSettlementResponse from(CardSettlementResult result) {
        return new CardSettlementResponse(result.captureEntryId(), result.capturedMinor(),
                result.releasedRemainderMinor(), result.excessMinor(), result.excessEntryId());
    }
}
