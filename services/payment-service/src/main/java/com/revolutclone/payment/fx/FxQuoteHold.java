package com.revolutclone.payment.fx;

import java.time.Instant;
import java.util.UUID;

/**
 * What payment-service needs from fx-service's {@code InternalQuoteResponse} to complete a
 * cross-currency transfer's own saga - plans/phase-04-fx-multicurrency.md task 8. Everything
 * here is fx-service's own computation (the mid, the spread, the rounding); this service only
 * carries it into {@code FxExecutionRequestedV1}, exactly as fx-service's own execute path does
 * for a self-service exchange.
 */
public record FxQuoteHold(
        UUID quoteId,
        UUID reservationId,
        UUID buyLedgerAccountId,
        String sellCurrency,
        long sellMinor,
        String buyCurrency,
        long poolMidMinor,
        long clientBuyMinor,
        long feeMinor,
        long pnlMinor,
        Instant expiresAt) {
}
