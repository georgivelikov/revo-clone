package com.revolutclone.analytics.query;

/**
 * One {@code (category, currency)} bucket of {@code monthly_spend} for a single month - task
 * 06.8's monthly summary line item. Money is {@code long} minor units plus an ISO currency code,
 * per CLAUDE.md's hard rule 1 - never a converted or summed figure across currencies.
 */
public record CategorySpend(String category, String currency, long totalMinor, int txnCount) {
}
