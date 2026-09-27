package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;
import java.util.UUID;

/**
 * Published by {@code fx-service} on {@code fx.quote.v1}, keyed by {@code quoteId}, when a user
 * executes an {@code ACTIVE} quote. {@code ledger-service} consumes it, captures the quote's
 * reservation and posts plans/phase-04-fx-multicurrency.md's FX entry.
 *
 * <p><strong>It carries amounts, not rates.</strong> The five (or six) legs are priced by
 * fx-service, which owns the mid, the spread and the rounding, and are simply reported here:
 * the pool gives up {@code poolMidMinor} at the mid rate, the client receives
 * {@code clientBuyMinor} at the client rate, the difference is {@code feeMinor} of revenue, and
 * {@code pnlMinor} is whatever sub-unit residual the three roundings failed to reconcile. Sending
 * the two rates instead would make the ledger re-derive the split and give the system two
 * implementations of the same arithmetic to disagree about; the ledger's job is that the entry
 * balances per currency, which it checks for itself regardless of who computed the numbers.
 *
 * <p>{@code pnlMinor} is signed - positive when the pool gave up more than the client and the fee
 * together, negative when it gave up less - and is the only field here that may be zero or
 * negative. The consumer turns its sign into a posting direction against {@code FX_PNL}.
 *
 * <p><strong>The sell account is deliberately absent.</strong> The money leaves whichever account
 * {@code reservationId} holds, and the ledger's own reservation row is the authority on that;
 * naming it again here would create a second version of the truth that a malformed or stale event
 * could contradict. {@code buyLedgerAccountId} has no such source and must be carried: for an
 * exchange it is the caller's own wallet in the buy currency, and for a cross-currency transfer
 * (Phase 04 task 8) it is the recipient's.
 */
public record FxExecutionRequestedV1(
        UUID quoteId,
        UUID userId,
        UUID reservationId,
        UUID buyLedgerAccountId,
        String sellCurrency,
        long sellMinor,
        String buyCurrency,
        long poolMidMinor,
        long clientBuyMinor,
        long feeMinor,
        long pnlMinor) implements DomainEvent {
}
