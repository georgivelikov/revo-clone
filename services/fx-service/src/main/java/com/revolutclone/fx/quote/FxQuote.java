package com.revolutclone.fx.quote;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code fx_quote} (plans/phase-04-fx-multicurrency.md's schema), as an immutable
 * snapshot. A quote is written once and then only ever transitions state, so there is nothing for a
 * mutable entity to buy.
 *
 * <p><strong>Why the two rates are {@link String} and why this is not a JPA entity.</strong> The
 * columns are {@code numeric(20,10)}; the natural Java type for that is the one CLAUDE.md's hard
 * rule 1 confines to {@code FxMath} and {@code Money.toMajor}, and the project's tripwire enforces
 * that by path. A JPA {@code AttributeConverter} would put that type back into an ordinary class, so
 * rates travel as exact decimal text - exactly as they do on {@code FxRateTickV1} and through
 * {@code FxMath} - and {@link FxQuoteRepository} casts at the SQL boundary instead
 * ({@code cast(? as numeric)} on write, {@code ::text} on read). That also disposes of the phase's
 * own warning that "Hibernate will happily truncate on write": no Java-side decimal type is
 * involved in the round trip at all, and {@code QuoteReservesFundsTest} asserts the stored rates
 * come back digit-for-digit at the column's full scale.
 *
 * <p>{@code reservationId} is the ledger's hold on {@link #sellMinor} of {@link #sellCurrency}. It is
 * nullable in the schema but always set by the quoting path, which reserves before it persists -
 * deliberately, so that a crash in between leaves a hold whose own expiry releases it rather than a
 * quote with no funds behind it.
 */
public record FxQuote(
        UUID id,
        String idempotencyKey,
        UUID userId,
        String sellCurrency,
        String buyCurrency,
        long sellMinor,
        long buyMinor,
        String midRate,
        String clientRate,
        int spreadBps,
        UUID reservationId,
        FxQuoteState state,
        Instant expiresAt,
        Instant createdAt) {

    /** True when {@code asOf} is at or after {@code expiresAt} - always asked with the server clock. */
    public boolean isExpiredAt(Instant asOf) {
        return !asOf.isBefore(expiresAt);
    }

    /**
     * The five or six legs this quote will be posted as when it is executed. Derived rather than
     * stored: the amounts follow from the sell amount and the two rates, all three of which are on
     * the row, so storing them again would only create a second version of the truth to disagree
     * with.
     */
    public FxLegs legs() {
        return FxLegs.of(sellMinor, sellCurrency, buyCurrency, midRate, clientRate);
    }
}
