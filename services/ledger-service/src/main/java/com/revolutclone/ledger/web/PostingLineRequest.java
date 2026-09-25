package com.revolutclone.ledger.web;

import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.ledger.posting.PostingLine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/** One leg of a {@link PostEntryRequest}. Mirrors {@link PostingLine} at the HTTP boundary. */
public record PostingLineRequest(
        @NotNull UUID ledgerAccountId,
        @NotNull PostingDirection direction,
        @Positive long amountMinor,
        @NotBlank String currency,
        UUID reservationId) {

    PostingLine toPostingLine() {
        return new PostingLine(ledgerAccountId, direction, amountMinor, currency, reservationId);
    }
}
