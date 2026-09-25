package com.revolutclone.ledger.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body for the funding endpoint (task 02.11): "A funding endpoint (EXTERNAL_CLEARING -> wallet)
 * so later phases have money to move." {@code currency} must match the target account's own
 * currency - included as an explicit check rather than inferred, matching {@code PostEntryRequest}'s
 * own convention of never silently assuming a currency.
 */
public record FundWalletRequest(@NotNull @Positive Long amountMinor, @NotBlank String currency) {
}
