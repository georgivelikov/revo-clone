package com.revolutclone.fx.quote;

import com.revolutclone.money.FxMath;
import com.revolutclone.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The legs of plans/phase-04-fx-multicurrency.md's FX posting, computed from a quote. Five legs, or
 * six when a sub-unit rounding residual is left over:
 *
 * <pre>
 *   DEBIT   alice:EUR          sellMinor        EUR   &lt;- the client loses the sell currency
 *   CREDIT  system:fx-pool     sellMinor        EUR   &lt;- the pool gains it
 *   ------------------------------------------------- the sell currency nets to zero
 *   DEBIT   system:fx-pool     poolMidMinor     GBP   &lt;- the pool gives up the MID amount
 *   CREDIT  alice:GBP          clientBuyMinor   GBP   &lt;- the client gains it at the CLIENT rate
 *   CREDIT  system:fee-income  feeMinor         GBP   &lt;- the spread, as revenue
 *   CREDIT  system:fx-pnl      pnlMinor         GBP   &lt;- only when the three roundings do not cancel
 *   ------------------------------------------------- the buy currency nets to zero
 * </pre>
 *
 * <p><strong>Why three separate conversions rather than two and a subtraction.</strong> The
 * temptation is to compute the fee as {@code poolMidMinor - clientBuyMinor}, which balances the entry
 * by construction and never needs an {@code FX_PNL} leg at all. It is also wrong in the way the phase
 * warns about twice over: the fee would then absorb whatever the two roundings happened to leave
 * behind, so {@code FEE_INCOME} would answer "the spread, plus or minus rounding noise" instead of
 * "the spread". Each leg is therefore converted at its own rate - the pool at the mid, the client at
 * the client rate, the fee at exactly the difference between them - and the at-most-one minor unit
 * the three roundings fail to reconcile is posted explicitly to {@code FX_PNL}. That is the "sub-unit
 * rounding residual adds a sixth leg" the phase describes, and it is a computed figure rather than a
 * plug because {@link FxMath#convertWithResidual} hands back the discarded fraction.
 *
 * <p>The bound on that residual follows from the same identity: with
 * {@code clientRate + spreadRate == mid} exactly (see {@link FxMath#spreadRate}), the unrounded buy
 * amounts satisfy {@code midExact == clientExact + feeExact}, so
 * {@code pnl = -residual(mid) + residual(client) + residual(fee)} where each term is under half a
 * minor unit. {@code pnl} is therefore always in {@code {-1, 0, +1}} - a rounding residual and nothing
 * else, which is exactly what {@code SpreadIsRevenueTest} pins down.
 *
 * <p>This type computes; it does not post. Turning it into a journal entry - with the
 * {@code reservation_id} on the client's sell leg so {@code available} is not decremented twice - is
 * task 04.5's job, in ledger-service.
 */
public record FxLegs(
        String sellCurrency,
        long sellMinor,
        String buyCurrency,
        long poolMidMinor,
        long clientBuyMinor,
        long feeMinor,
        long pnlMinor,
        String midResidual,
        String clientResidual,
        String feeResidual) {

    /** DEBIT or CREDIT. Deliberately fx-service's own enum: the ledger's is the ledger's. */
    public enum Direction {
        DEBIT, CREDIT
    }

    /** Which side of the exchange a leg belongs to, named for the account it will be posted against. */
    public enum Role {
        CLIENT_WALLET, FX_POOL, FEE_INCOME, FX_PNL
    }

    /** One leg, in the shape the ledger will eventually be asked to post it. */
    public record Leg(Role role, Direction direction, long amountMinor, String currency) {

        /** The leg's contribution to the phase's verification SQL: {@code CREDIT} positive, {@code DEBIT} negative. */
        public long signedMinor() {
            return direction == Direction.CREDIT ? amountMinor : -amountMinor;
        }
    }

    /**
     * Derives the legs for selling {@code sellMinor} of {@code sellCurrency} into {@code buyCurrency}
     * at {@code midRate}, with the client served at {@code clientRate}.
     *
     * @throws IllegalArgumentException if either rate is unusable, or the client rate is better than
     *                                  the mid (which would mean paying the client the spread)
     */
    public static FxLegs of(long sellMinor, String sellCurrency, String buyCurrency,
            String midRate, String clientRate) {
        Money sell = Money.of(sellMinor, sellCurrency);
        String spreadRate = FxMath.spreadRate(midRate, clientRate);

        FxMath.FxConversion atMid = FxMath.convertWithResidual(sell, midRate, buyCurrency);
        FxMath.FxConversion atClientRate = FxMath.convertWithResidual(sell, clientRate, buyCurrency);
        // A zero spread has no fee rate to convert at, and FxMath rightly refuses a zero rate: 0 bps
        // is a legitimate "no spread today" configuration, not a broken rate.
        FxMath.FxConversion atSpreadRate = isZeroRate(spreadRate)
                ? new FxMath.FxConversion(Money.of(0L, buyCurrency), "0")
                : FxMath.convertWithResidual(sell, spreadRate, buyCurrency);

        long poolMidMinor = atMid.converted().minor();
        long clientBuyMinor = atClientRate.converted().minor();
        long feeMinor = atSpreadRate.converted().minor();

        return new FxLegs(sellCurrency.toUpperCase(Locale.ROOT), sellMinor, buyCurrency.toUpperCase(Locale.ROOT),
                poolMidMinor, clientBuyMinor, feeMinor,
                poolMidMinor - clientBuyMinor - feeMinor,
                atMid.residualMinorFraction(), atClientRate.residualMinorFraction(),
                atSpreadRate.residualMinorFraction());
    }

    /** Every leg, in posting order - five, or six when there is a residual to place. */
    public List<Leg> legs() {
        List<Leg> legs = new ArrayList<>(6);
        legs.add(new Leg(Role.CLIENT_WALLET, Direction.DEBIT, sellMinor, sellCurrency));
        legs.add(new Leg(Role.FX_POOL, Direction.CREDIT, sellMinor, sellCurrency));
        legs.add(new Leg(Role.FX_POOL, Direction.DEBIT, poolMidMinor, buyCurrency));
        legs.add(new Leg(Role.CLIENT_WALLET, Direction.CREDIT, clientBuyMinor, buyCurrency));
        legs.add(new Leg(Role.FEE_INCOME, Direction.CREDIT, feeMinor, buyCurrency));
        if (pnlMinor > 0) {
            legs.add(new Leg(Role.FX_PNL, Direction.CREDIT, pnlMinor, buyCurrency));
        } else if (pnlMinor < 0) {
            legs.add(new Leg(Role.FX_PNL, Direction.DEBIT, -pnlMinor, buyCurrency));
        }
        return legs;
    }

    /**
     * {@code sum(CREDIT) - sum(DEBIT)} for one currency, the same expression as the phase's
     * verification SQL. Zero for both currencies of a well-formed exchange - "the ledger's invariant
     * is per currency", so this is asserted per currency and never over the entry as a whole.
     */
    public long netFor(String currency) {
        String wanted = currency.toUpperCase(Locale.ROOT);
        return legs().stream()
                .filter(leg -> leg.currency().equals(wanted))
                .mapToLong(Leg::signedMinor)
                .sum();
    }

    /** Six when a residual has to be posted, five when the three roundings cancelled. */
    public int legCount() {
        return legs().size();
    }

    private static boolean isZeroRate(String rate) {
        return rate.chars().noneMatch(character -> character >= '1' && character <= '9');
    }
}
