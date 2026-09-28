package com.revolutclone.card.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * The body of {@code PUT /api/cards/{cardId}/limits/{period}}. No currency field - see
 * {@code CardLimitService}'s javadoc: the currency is always the card's own funding wallet's,
 * resolved server-side rather than trusted from the request.
 */
public record UpsertCardLimitRequest(

        @NotNull(message = "amountMinor is required")
        @Positive(message = "amountMinor must be positive")
        Long amountMinor) {
}
