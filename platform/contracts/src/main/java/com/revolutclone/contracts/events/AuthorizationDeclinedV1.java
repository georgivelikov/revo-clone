package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/**
 * The other half of step 7: the issuer said no, and why. Published by {@code card-service} on
 * {@code card.authorization.v1}.
 *
 * <p><strong>{@code authorizationId}, {@code cardId}, {@code userId} and {@code panLast4} are null
 * for exactly one reason: {@code CARD_NOT_FOUND}.</strong> A PAN that resolves to no card has
 * nothing to record a row against - {@code card_authorization.card_id} is {@code not null} and
 * references {@code card(id)} - and nobody to notify either. That decline is still published,
 * because "the network presented a card number we have never issued" is a fact worth seeing, and
 * such a message is keyed by {@code networkRef} rather than by a card id it does not have.
 *
 * <p>{@code declineReason} is the vocabulary of plans/phase-05-cards.md's flow: {@code
 * CARD_NOT_FOUND}, {@code CARD_FROZEN}, {@code CARD_EXPIRED}, {@code LIMIT_EXCEEDED},
 * {@code INSUFFICIENT_FUNDS}. A string on the wire rather than an enum, so adding a reason is a
 * backwards-compatible change for every consumer.
 */
public record AuthorizationDeclinedV1(
        UUID authorizationId,
        UUID cardId,
        UUID userId,
        String panLast4,
        String networkRef,
        String merchantName,
        String merchantMcc,
        String merchantCountry,
        long amountMinor,
        String currency,
        String declineReason) implements DomainEvent {
}
