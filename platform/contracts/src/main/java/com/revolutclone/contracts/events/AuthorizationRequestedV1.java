package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

/**
 * The card network asking "will you cover this?" - published by {@code simulator-service} on
 * {@code card.authorization.v1} and consumed by {@code card-service}'s decision chain.
 *
 * <p><strong>Keyed by {@code cardId}, which is deliberately not a field here.</strong>
 * plans/phase-05-cards.md's payload sketch is {@code { networkRef, pan, amountMinor, currency,
 * merchant… }} - a real network identifies the card by its number and nothing else, so the
 * consumer resolves it by PAN hash (step 1 of the chain). The partition key is still the card id,
 * because "two taps on the same card are evaluated in order against the same limits" is the whole
 * point of CLAUDE.md hard rule 5; the publisher knows which card it is spending on even though
 * the message body speaks the network's language.
 *
 * <p>{@code networkRef} is the network's own id for this authorization and is the idempotency
 * anchor for the entire card flow - auth, settlement and reversal all quote it. The network
 * genuinely resends, so {@code card_authorization.network_ref} is unique and a redelivery under a
 * fresh {@code eventId} still produces exactly one authorization.
 *
 * <p>This is the one event in the project that carries a full PAN, because the network's request
 * genuinely does. It must never be logged: {@code card-service}'s masking converter (task 11) and
 * {@code PanNeverLoggedTest} exist for this record.
 */
public record AuthorizationRequestedV1(
        String networkRef,
        String pan,
        long amountMinor,
        String currency,
        String merchantName,
        String merchantMcc,
        String merchantCountry) implements DomainEvent {
}
