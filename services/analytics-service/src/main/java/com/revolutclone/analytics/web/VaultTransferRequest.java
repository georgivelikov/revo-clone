package com.revolutclone.analytics.web;

import com.revolutclone.analytics.vault.TransferDirection;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * The body of {@code POST /api/analytics/vaults/{id}/transfer}. {@code walletAccountId} is the
 * user's own wallet account id - the client already has this from {@code GET /api/accounts/me},
 * per the task's own design detail. {@code currency} is required even though it could be assumed
 * to match the vault's, the same reasoning payment-service's own {@code CreatePaymentRequest}
 * documents: a mismatch becomes a {@code CURRENCY_MISMATCH} rejection from payment-service, not a
 * silently reinterpreted amount decided here.
 */
public record VaultTransferRequest(
        @NotNull TransferDirection direction,

        @NotNull UUID walletAccountId,

        @Positive long amountMinor,

        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an uppercase ISO-4217 code")
        String currency) {
}
