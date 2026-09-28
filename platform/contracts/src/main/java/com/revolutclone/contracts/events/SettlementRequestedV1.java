package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

/**
 * The merchant's batch arrived: it is now claiming the money an authorization only held. Published by
 * {@code simulator-service} on {@code card.authorization.v1}, keyed by {@code cardId} so that a card's
 * settlements are processed in the order the network sent them, exactly as its authorizations are.
 *
 * <p><strong>{@code networkRef} is what this is about, not an authorization id.</strong> The network
 * never learned our id for the decision - it quotes its own reference, days later, which is why that
 * column is unique in {@code card_authorization} and why every message in this flow is matched on it.
 *
 * <p><strong>{@code settledAmountMinor} is in the merchant's currency</strong> - the same currency the
 * authorization named, because it is the same merchant asking for the same purchase. It is
 * deliberately not required to equal the authorized amount: a restaurant adds a tip, a fuel pump
 * authorizes a notional amount and settles the litres, a hotel settles the nights actually stayed.
 * Under-settlement releases the difference, over-settlement posts it. See
 * plans/phase-05-cards.md's settlement table.
 *
 * <p>Carries no rate and no billing amount. The choice recorded in {@code CardFxDetail} is to hold at
 * the authorization's rate and settle at the authorization's rate, so the conversion this needs is
 * already on the row - re-quoting one here would let a message days old contradict what the holder was
 * shown at the till.
 */
public record SettlementRequestedV1(
        String networkRef,
        long settledAmountMinor,
        String currency) implements DomainEvent {
}
