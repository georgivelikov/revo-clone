package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;
import java.util.UUID;

/**
 * The hold is gone and no money ever moved. Published by {@code card-service} on
 * {@code card.authorization.v1}, keyed by {@code cardId}.
 *
 * <p><strong>Carries no {@code ledgerEntryId}, because there is none</strong> - and that absence is
 * the assertion {@code ReversalTest} makes. plans/phase-05-cards.md: "Reversal: release the hold, no
 * posting". The holder's available balance goes back up, their total never changed, and the feed row
 * stops being pending without ever becoming a transaction.
 *
 * <p>{@code releasedMinor}/{@code releasedCurrency} are the wallet's side - what the hold was for, so
 * a subscriber can say "€42.00 is available again" without looking the authorization up.
 */
public record AuthorizationReversedV1(
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
