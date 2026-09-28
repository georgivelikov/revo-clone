package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;
import java.util.UUID;

/**
 * The money actually moved. Published by {@code card-service} on {@code card.authorization.v1}, keyed
 * by {@code cardId}, once a settlement has been posted to the ledger.
 *
 * <p><strong>This is the event the whole phase exists to produce.</strong> Its predecessor,
 * {@link AuthorizationApprovedV1}, named a hold and no posting; this one names
 * {@code ledgerEntryId}, and that difference is what turns a dashed pending row in the feed into a
 * settled one and makes available and total balance converge again.
 *
 * <p><strong>Two settled amounts, as the approval carried two authorized ones.</strong>
 * {@code merchantSettledMinor}/{@code currency} is what the merchant captured in its own currency, and
 * {@code settledMinor}/{@code settledCurrency} is the same capture in the wallet's - converted at the
 * authorization's own rate, not a fresh one, so the figure the holder saw at the till is the figure
 * that leaves their account. For a domestic purchase the two are identical.
 *
 * <p><strong>{@code settledMinor} is not necessarily the amount authorized.</strong> A partial
 * settlement is less (and {@code releasedMinor} says how much went back to the holder's available
 * balance); an over-settlement is more, and then {@code excessMinor} is positive and
 * {@code excessLedgerEntryId} names the second entry that posted it. A subscriber that only wants
 * "what was I charged?" reads {@code settledMinor} and can ignore the rest.
 *
 * <p><strong>{@code excessMinor} is the flag, not a log line.</strong> plans/phase-05-cards.md asks
 * that an over-settlement be "flagged, not silently swallowed", and it is the one path that can
 * legitimately leave a wallet negative - so it is on the wire, where a notification or an analytics
 * projection can see it, rather than only in card-service's own table.
 *
 * <p>{@code state} is the authorization's new state as a string - {@code SETTLED} or
 * {@code PARTIALLY_SETTLED} - so a consumer need not re-derive it from the amounts.
 */
public record AuthorizationSettledV1(
        UUID authorizationId,
        UUID cardId,
        UUID userId,
        UUID accountId,
        String panLast4,
        String networkRef,
        String merchantName,
        String merchantMcc,
        String merchantCountry,
        long merchantSettledMinor,
        String currency,
        long settledMinor,
        String settledCurrency,
        long releasedMinor,
        long excessMinor,
        UUID ledgerEntryId,
        UUID excessLedgerEntryId,
        String state) implements DomainEvent {
}
