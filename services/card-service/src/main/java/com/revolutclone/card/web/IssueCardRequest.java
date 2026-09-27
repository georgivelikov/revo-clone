package com.revolutclone.card.web;

import com.revolutclone.card.domain.CardType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The body of {@code POST /api/cards}. No {@code userId} - the owner is the authenticated JWT
 * subject, never something a caller can choose for somebody else, same rule as
 * {@code CreatePaymentRequest}.
 *
 * <p>{@code pin} is the cardholder's own choice of step-up PIN, required at issuance rather than
 * generated, so the person revealing the card later is proving they know a secret they, not the
 * system, chose - see plans/phase-05-cards.md's Card screen note on step-up.
 */
public record IssueCardRequest(

        @NotNull(message = "accountId is required")
        UUID accountId,

        CardType type,

        @Size(max = 60, message = "label must be at most 60 characters")
        String label,

        @NotNull(message = "pin is required")
        @Pattern(regexp = "^\\d{4,6}$", message = "pin must be 4 to 6 digits")
        String pin) {

    public IssueCardRequest {
        if (type == null) {
            type = CardType.VIRTUAL;
        }
    }
}
