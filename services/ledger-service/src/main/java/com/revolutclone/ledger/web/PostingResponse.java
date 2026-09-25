package com.revolutclone.ledger.web;

import com.revolutclone.ledger.domain.Posting;
import com.revolutclone.ledger.domain.PostingDirection;
import java.util.UUID;

public record PostingResponse(
        UUID id,
        UUID entryId,
        UUID ledgerAccountId,
        PostingDirection direction,
        long amountMinor,
        String currency,
        UUID reservationId,
        int seq) {

    static PostingResponse from(Posting posting) {
        return new PostingResponse(posting.getId(), posting.getEntryId(), posting.getLedgerAccountId(),
                posting.getDirection(), posting.getAmountMinor(), posting.getCurrency(),
                posting.getReservationId(), posting.getSeq());
    }
}
