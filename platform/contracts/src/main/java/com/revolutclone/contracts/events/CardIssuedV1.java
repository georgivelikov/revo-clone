package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/**
 * Published by {@code card-service} on {@code card.authorization.v1}, keyed by {@code cardId} -
 * the same topic the authorization flow's {@code AuthorizationRequestedV1}/{@code
 * AuthorizationApprovedV1}/{@code AuthorizationDeclinedV1} travel on (plans/phase-05-cards.md's
 * only declared card topic; no separate issuance topic exists in plans/phase-00-foundations.md's
 * catalogue). Same precedent as {@code ledger.entry.v1}, which carries several distinct event
 * types for one domain rather than one topic per type.
 *
 * <p>Deliberately carries no PAN, CVV or any encrypted material - only what a subscriber (a
 * push notification, an analytics projection) needs to know a card now exists.
 */
public record CardIssuedV1(
        UUID cardId,
        UUID userId,
        UUID accountId,
        String type,
        String panLast4,
        int expiryMonth,
        int expiryYear) implements DomainEvent {
}
