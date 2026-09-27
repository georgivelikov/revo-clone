package com.revolutclone.fx.ledger;

import java.time.Instant;
import java.util.UUID;

/**
 * The reservation the ledger created, as it answered. A subset of the ledger's own
 * {@code ReservationResponse} - {@code state} and {@code referenceId} are on the wire but fx-service
 * has nothing to decide from them, and Boot's Jackson defaults ignore unknown properties.
 *
 * <p>{@code expiresAt} is echoed back rather than assumed: the hold's own timeout is what releases
 * the funds if this service dies between reserving and persisting the quote, so the value that
 * matters is the one the ledger recorded, not the one fx-service asked for.
 */
public record HeldFunds(UUID id, UUID ledgerAccountId, long amountMinor, String currency, Instant expiresAt) {
}
