package com.revolutclone.ledger.chaos;

/**
 * Partial update: a {@code null} field leaves that lever exactly as it was, so the caller can
 * set {@code delayMs} without disturbing {@code failRatePercent} or {@code rejectAll}.
 */
public record ChaosLedgerCommand(Long delayMs, Integer failRatePercent, Boolean rejectAll) {
}
