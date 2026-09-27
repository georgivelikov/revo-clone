package com.revolutclone.fx.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * plans/phase-04-fx-multicurrency.md: {@code POST /api/fx/quotes { sellCurrency, buyCurrency,
 * sellMinor | buyMinor }}.
 *
 * <p>Both amounts are nullable {@code Long} and both are validated as positive when present; "exactly
 * one of the two" is a relationship between fields and is enforced in {@code QuoteService}, which is
 * also where the equivalent rule would have to live for a quote created by any other caller.
 */
public record CreateQuoteRequest(
        @NotBlank @Size(min = 3, max = 3) String sellCurrency,
        @NotBlank @Size(min = 3, max = 3) String buyCurrency,
        @Positive Long sellMinor,
        @Positive Long buyMinor) {
}
