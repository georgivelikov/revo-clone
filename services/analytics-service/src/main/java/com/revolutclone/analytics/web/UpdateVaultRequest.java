package com.revolutclone.analytics.web;

import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

/**
 * The body of {@code PUT /api/analytics/vaults/{id}}: goal and round-up settings only - name,
 * currency and the backing account never change once a vault is created.
 */
public record UpdateVaultRequest(
        @PositiveOrZero Long goalMinor,
        boolean roundupEnabled,
        UUID roundupSourceCardId) {
}
