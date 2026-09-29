package com.revolutclone.analytics.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

/**
 * The body of {@code POST /api/analytics/vaults}. {@code goalMinor}/{@code roundupEnabled}/
 * {@code roundupSourceCardId} are stored inert for now (task 06.6) - the round-up rule itself is
 * task 06.7, per plans/phase-06-vaults-analytics.md's own scope note.
 */
public record CreateVaultRequest(
        @NotBlank String name,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be an ISO-4217 code")
        String currency,

        @PositiveOrZero Long goalMinor,

        boolean roundupEnabled,

        UUID roundupSourceCardId) {
}
