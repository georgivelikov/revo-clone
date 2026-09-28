package com.revolutclone.simulator.card;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * The body of {@code POST /simulator/cards/spend} and {@code POST /simulator/cards/spend/batch}.
 * Only {@code cardId} is required - plans/phase-05-cards.md's Verification curl spells out
 * {@code merchant}/{@code amountMinor}/{@code currency} explicitly, but a caller (a UI "spend"
 * button, {@code spendBatch}'s own loop) is equally entitled to leave any of them to
 * {@link MerchantCatalogue}.
 */
public record SpendRequest(

        @NotNull(message = "cardId is required")
        UUID cardId,

        String merchant,

        @Positive(message = "amountMinor must be positive")
        Long amountMinor,

        String currency) {
}
