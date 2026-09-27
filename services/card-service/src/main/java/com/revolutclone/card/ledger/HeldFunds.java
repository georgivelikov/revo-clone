package com.revolutclone.card.ledger;

import java.time.Instant;
import java.util.UUID;

/**
 * The reservation the ledger created, as it answered. A subset of the ledger's own
 * {@code ReservationResponse}; Boot's Jackson defaults ignore the properties this service has
 * nothing to decide from.
 *
 * <p>{@code expiresAt} is echoed back rather than assumed: the hold's own expiry is what releases
 * the funds if this service dies between holding and recording the authorization, so the value that
 * matters is the one the ledger recorded.
 */
public record HeldFunds(UUID id, UUID ledgerAccountId, long amountMinor, String currency, Instant expiresAt) {
}
