package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;
import java.util.UUID;

/**
 * Published by {@code fx-service} on {@code fx.quote.v1}, keyed by {@code quoteId}, when an
 * execution that was already accepted could not be posted - the compensation row of
 * plans/phase-04-fx-multicurrency.md's table: "Ledger rejects the capture (funds moved after the
 * hold) - fx-service marks the quote {@code CANCELLED}, releases the reservation, emits
 * {@code FxExecutionFailedV1}."
 *
 * <p>The quote is {@code CANCELLED} and its reservation released by the time this is published, so
 * a consumer's only job is to tell the user; there is nothing further to unwind. {@code reason} is
 * the ledger's own {@code EntryRejectedV1.reason}, passed through rather than re-worded, so the
 * two services' logs can be read against each other.
 */
public record FxExecutionFailedV1(
        UUID quoteId,
        UUID userId,
        String reason) implements DomainEvent {
}
