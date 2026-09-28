package com.revolutclone.simulator.card;

import java.util.UUID;

/**
 * This simulator's own record of one authorization it created - the merchant network's "file," in
 * miniature. {@code networkRef} is not a field: it is the key {@link SpendRegistry} stores this
 * under, exactly as it is the key everything in the real flow is matched on.
 *
 * <p>Exists only because {@code SettlementRequestedV1}/{@code ReversalRequestedV1} carry no
 * {@code cardId} (see those contracts' javadoc) while every message on {@code card.authorization.v1}
 * still has to be <em>keyed</em> by one (CLAUDE.md hard rule 5) - this is where the simulator, which
 * made the original request, keeps the card id it already knew so a later settlement or reversal
 * can still key its own message correctly.
 */
record PendingSpend(UUID cardId, String merchantName, String merchantMcc, String merchantCountry,
        long amountMinor, String currency, int settleOnDay) {
}
