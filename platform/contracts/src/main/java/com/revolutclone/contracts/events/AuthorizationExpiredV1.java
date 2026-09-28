package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;
import java.util.UUID;

/**
 * Seven days passed and the merchant never claimed the money, so the hold is gone and no money ever
 * moved. Published by {@code card-service} on {@code card.authorization.v1}, keyed by {@code cardId}.
 *
 * <p>plans/phase-05-cards.md's settlement table: "Expiry (7 days, no settlement) - sweep releases the
 * hold". The shape is deliberately {@link AuthorizationReversedV1}'s, because the holder-visible
 * outcome is the same one - a pending row that stops being pending without ever becoming a
 * transaction - and a subscriber that renders one should need no new code to render the other. What
 * differs is only <em>why</em>, which is the event type itself.
 *
 * <p><strong>No {@code ledgerEntryId}, because there is none.</strong> An expiry, like a reversal, is
 * the absence of an accounting event: available goes back up, total never changed.
 */
public record AuthorizationExpiredV1(
        UUID authorizationId,
        UUID cardId,
        UUID userId,
        UUID accountId,
        String panLast4,
        String networkRef,
        String merchantName,
        long releasedMinor,
        String releasedCurrency) implements DomainEvent {
}
