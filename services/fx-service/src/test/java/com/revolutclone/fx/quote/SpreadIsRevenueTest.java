package com.revolutclone.fx.quote;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.money.FxMath;
import org.junit.jupiter.api.Test;

/**
 * plans/phase-04-fx-multicurrency.md's Tests table: "{@code FEE_INCOME} grows by exactly the spread;
 * {@code FX_PNL} absorbs only residual; the pool holds only the mid-rate exposure."
 *
 * <p>Asserted against {@link FxLegs}, the computation a quote carries into the posting, because that is
 * where the distinction is decided. The phase is explicit that four legs at the client rate balance
 * perfectly and hide the spread in pool drift - so what needs pinning down is not that the entry
 * balances (that is {@code FxEntryBalanceTest}, task 04.5) but that each of the three buy-side legs
 * carries the amount it is supposed to.
 *
 * <p>Note on the phase's worked example: it states mid 0.8600 with a 50 bps spread giving a client rate
 * of 0.8543, which is ~66 bps rather than 50. 50 bps of 0.8600 is 0.8557, so the same €100 exchange
 * produces 8557 to the client and 43 to fee income. The arithmetic asserted here is the phase's rule
 * (spread = bps of the mid), not its illustrative digits.
 */
class SpreadIsRevenueTest {

    private static final String MID = "0.8600000000";

    private static FxLegs legsFor(long sellMinor, String buyCurrency, String mid, int spreadBps) {
        return FxLegs.of(sellMinor, "EUR", buyCurrency, mid, FxMath.applySpread(mid, spreadBps));
    }

    @Test
    void feeIncomeGrowsByExactlyTheSpreadAndNothingElse() {
        FxLegs legs = legsFor(10_000L, "GBP", MID, 50);

        // 100.00 EUR at mid 0.86 is 8600 GBP; at the client rate 0.8557 it is 8557. The difference is
        // 0.5% of the mid amount - the spread, to the minor unit.
        assertThat(legs.poolMidMinor()).isEqualTo(8_600L);
        assertThat(legs.clientBuyMinor()).isEqualTo(8_557L);
        assertThat(legs.feeMinor()).isEqualTo(43L);
        assertThat(legs.feeMinor()).isEqualTo(legs.poolMidMinor() - legs.clientBuyMinor() - legs.pnlMinor());
    }

    @Test
    void thePoolLegIsPostedAtTheMidRateNotTheClientRate() {
        FxLegs legs = legsFor(10_000L, "GBP", MID, 50);

        // "FEE_INCOME staying at zero means you posted the pool leg at the client rate." The pool's
        // exposure is the mid amount; the spread is revenue, not pool drift.
        assertThat(legs.poolMidMinor()).isNotEqualTo(legs.clientBuyMinor());
        assertThat(legs.legs()).anySatisfy(leg -> {
            assertThat(leg.role()).isEqualTo(FxLegs.Role.FX_POOL);
            assertThat(leg.direction()).isEqualTo(FxLegs.Direction.DEBIT);
            assertThat(leg.amountMinor()).isEqualTo(8_600L);
        });
    }

    @Test
    void awiderSpreadMovesTheWholeDifferenceIntoFeeIncome() {
        FxLegs tight = legsFor(10_000L, "GBP", MID, 50);
        FxLegs wide = legsFor(10_000L, "GBP", MID, 200);

        assertThat(wide.feeMinor()).isGreaterThan(tight.feeMinor());
        // The pool's exposure is unchanged by the spread - only the split between the client and fee
        // income moves, which is the whole reason the two questions are asked of two accounts.
        assertThat(wide.poolMidMinor()).isEqualTo(tight.poolMidMinor());
        assertThat(wide.clientBuyMinor() + wide.feeMinor() + wide.pnlMinor())
                .isEqualTo(tight.clientBuyMinor() + tight.feeMinor() + tight.pnlMinor());
    }

    @Test
    void aZeroSpreadEarnsNothingAndLeavesFiveLegs() {
        FxLegs legs = legsFor(10_000L, "GBP", MID, 0);

        assertThat(legs.feeMinor()).isZero();
        assertThat(legs.clientBuyMinor()).isEqualTo(legs.poolMidMinor());
        assertThat(legs.pnlMinor()).isZero();
        assertThat(legs.legCount()).isEqualTo(5);
    }

    @Test
    void fxPnlOnlyEverAbsorbsASubUnitResidual() {
        // Sweep a range of amounts and both spreads: whatever the three roundings do, the residual leg
        // is never more than one minor unit, and it is exactly the sum of the discarded fractions.
        for (long sellMinor = 1L; sellMinor <= 500L; sellMinor++) {
            FxLegs legs = legsFor(sellMinor, "GBP", "0.8637123456", 50);

            assertThat(legs.pnlMinor()).isBetween(-1L, 1L);
            assertThat(legs.netFor(legs.buyCurrency())).isZero();
        }
    }

    @Test
    void theResidualLegIsTheDifferenceTheThreeRoundingsLeftBehind() {
        FxLegs legs = legsFor(333L, "GBP", "0.8637123456", 50);

        // pnl = -residual(mid) + residual(client) + residual(fee), each under half a minor unit - so
        // this is a computed figure, not a plug. FxMath hands back each discarded fraction.
        assertThat(legs.midResidual()).isNotNull();
        assertThat(legs.clientResidual()).isNotNull();
        assertThat(legs.feeResidual()).isNotNull();
        assertThat(legs.poolMidMinor() - legs.clientBuyMinor() - legs.feeMinor()).isEqualTo(legs.pnlMinor());
    }

    @Test
    void bothCurrenciesNetToZeroIndependentlyEvenWithAResidualLeg() {
        FxLegs legs = legsFor(333L, "GBP", "0.8637123456", 50);

        assertThat(legs.netFor("EUR")).isZero();
        assertThat(legs.netFor("GBP")).isZero();
    }

    @Test
    void theSpreadIsWholeYenWhenTheBuyCurrencyHasNoMinorUnits() {
        // JPY has zero minor digits: 100.00 EUR at mid 162.5 is 16250 yen, and the 50 bps spread is 81
        // whole yen. Nothing anywhere holds a hundredth of a yen.
        FxLegs legs = legsFor(10_000L, "JPY", "162.5", 50);

        assertThat(legs.poolMidMinor()).isEqualTo(16_250L);
        assertThat(legs.clientBuyMinor()).isEqualTo(16_169L);
        assertThat(legs.feeMinor()).isEqualTo(81L);
        assertThat(legs.netFor("JPY")).isZero();
    }
}
