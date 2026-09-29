package com.revolutclone.analytics.query;

/**
 * One merchant's total settled spend over a period, for one currency - task 06.8's "top
 * merchants" list. Ranked by {@code totalMinor} descending within a currency; a merchant paid in
 * two currencies in the same period is two separate entries, never summed into one.
 */
public record TopMerchant(String merchantName, String currency, long totalMinor, int txnCount) {
}
