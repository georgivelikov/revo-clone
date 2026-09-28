package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

/**
 * The merchant will not be claiming the money after all - a cancelled order, a voided pre-auth, a
 * card-not-present transaction the merchant abandoned. Published by {@code simulator-service} on
 * {@code card.authorization.v1}, keyed by {@code cardId}.
 *
 * <p><strong>A reversal carries no amount, on purpose.</strong> plans/phase-05-cards.md's settlement
 * table is unambiguous - "Reversal: release the hold, no posting" - so there is nothing to be partial
 * about: the whole hold goes back and nothing is ever accounted for. A partial reversal in the real
 * schemes is expressed as a settlement for less, which is the {@link SettlementRequestedV1} path, and
 * having one amount-bearing message instead of two means the two can never disagree about what
 * "partial" means.
 *
 * <p>Distinct from the seven-day expiry, which reaches the same balance by a different route and says
 * something different about the merchant: an expiry means nobody ever came back, a reversal means
 * somebody came back to say no.
 */
public record ReversalRequestedV1(String networkRef) implements DomainEvent {
}
