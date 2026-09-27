package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;
import java.time.Instant;

/**
 * Published by {@code simulator-service} on {@code fx.rate.tick.v1}, keyed by {@code pair}.
 *
 * <p>{@code mid} is a decimal string (e.g. {@code "0.86000000"}), not a parsed
 * arbitrary-precision decimal field, even though plans/phase-04-fx-multicurrency.md's Design
 * detail shows this record with that type. CLAUDE.md's hard rule 1 confines that type to
 * {@code common-money}'s {@code FxMath} and {@code Money.toMajor} (see
 * {@code FxMath.randomWalkStep}), so every other type that carries a rate, including this wire
 * contract, carries it as exact decimal text instead and only ever parses it inside those two
 * places.
 *
 * <p>{@code pair} is always the alphabetical ordering of the two ISO currency codes (e.g.
 * {@code "EUR/GBP"}, never {@code "GBP/EUR"}) - {@code fx-service} derives the reverse direction
 * from this one rather than a second tick stream that would drift independently.
 */
public record FxRateTickV1(String pair, String mid, Instant asOf, String source) implements DomainEvent {
}
