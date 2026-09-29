package com.revolutclone.analytics.query;

import java.time.Instant;
import java.util.UUID;

/**
 * One settled card transaction underlying a category/month slice - task 06.8's drill-down,
 * "the list of underlying settled transactions a user can inspect after tapping a donut slice."
 * {@code amountMinor}/{@code currency} are the wallet-currency figures actually settled
 * ({@code CardAuthorizationFact.settledMinor}/{@code billingCurrency}) - the same amounts
 * {@code monthly_spend} sums, so this list's total matches the category total it drills into.
 */
public record DrillDownTransaction(UUID authorizationId, String merchantName, long amountMinor, String currency,
        Instant settledAt) {
}
