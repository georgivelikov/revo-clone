package com.revolutclone.money;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.revolutclone.money.FxMath.FxConversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FxMath#convertWithResidual} is {@link FxMath#convert} with the rounding remainder kept
 * instead of dropped. Both halves of that pair matter to the FX posting in
 * plans/phase-04-fx-multicurrency.md:
 *
 * <ul>
 *   <li><b>HALF_EVEN at exact boundaries.</b> A tie is the only case where a rounding mode makes a
 *       visible difference, and HALF_EVEN is chosen because its ties go both ways - so over many
 *       conversions they cancel instead of accruing to the house, which HALF_UP would do.</li>
 *   <li><b>The residual is always accounted for.</b> {@code converted + residual} reconstructs the
 *       unrounded product exactly. A residual that is silently discarded is money created or
 *       destroyed, and the ledger's per-currency zero-sum invariant is the thing that finds out.
 *       Every assertion below states the exact product independently, computed by hand, rather
 *       than deriving it from the same code under test.</li>
 * </ul>
 *
 * <p>Rates and residuals are exact decimal text here rather than a decimal type: CLAUDE.md hard
 * rule 1 keeps that type inside {@code FxMath} and {@code Money}, so this test is written the way
 * a real caller is obliged to write it.
 */
class FxConversionRoundingTest {

    @Nested
    @DisplayName("HALF_EVEN at an exact boundary")
    class HalfEvenAtAnExactBoundary {

        @Test
        void aTieRoundsDownWhenThatLeavesAnEvenLastDigit() {
            // 1.00 EUR * 1.005 = 1.00500 USD exactly = 100.5 cents -> tie -> 100, because 0 is even.
            FxConversion result = FxMath.convertWithResidual(Money.of(100, "EUR"), "1.005", "USD");

            assertThat(result.converted()).isEqualTo(Money.of(100, "USD"));
            assertThat(result.residualMinorFraction()).isEqualTo("0.5");
            assertThat(result.exactMinorUnits()).isEqualTo("100.5");
            assertThat(result.isExact()).isFalse();
        }

        @Test
        void aTieRoundsUpWhenThatLeavesAnEvenLastDigit() {
            // 1.00 EUR * 1.015 = 1.01500 USD exactly = 101.5 cents -> tie -> 102, because 2 is even.
            FxConversion result = FxMath.convertWithResidual(Money.of(100, "EUR"), "1.015", "USD");

            assertThat(result.converted()).isEqualTo(Money.of(102, "USD"));
            assertThat(result.residualMinorFraction()).isEqualTo("-0.5");
            assertThat(result.exactMinorUnits()).isEqualTo("101.5");
        }

        @Test
        void tiesInBothDirectionsProduceResidualsThatCancel() {
            // The whole point of HALF_EVEN: one tie rounds up and one rounds down, so the pair of
            // residuals sums to zero. Under HALF_UP both residuals would be -0.5 and the bias
            // would accumulate, half a minor unit per tie, always in the same direction.
            List<String> residuals = new ArrayList<>();
            for (String rate : List.of("1.005", "1.015")) {
                residuals.add(FxMath.convertWithResidual(Money.of(100, "EUR"), rate, "USD")
                        .residualMinorFraction());
            }

            assertThat(residuals).containsExactly("0.5", "-0.5");
        }

        @Test
        void theTieBreaksOnTheNeighboursParityNotOnTheDirectionOfTheRate() {
            // One rate, two amounts: 1.00 * 1.005 = 100.5 cents rounds down to 100, 3.00 * 1.005 =
            // 301.5 cents rounds up to 302. Both land on an even neighbour, which is why the
            // residuals come out with opposite signs and a run of conversions stays unbiased.
            assertThat(FxMath.convertWithResidual(Money.of(100, "EUR"), "1.005", "USD"))
                    .isEqualTo(new FxConversion(Money.of(100, "USD"), "0.5"));
            assertThat(FxMath.convertWithResidual(Money.of(300, "EUR"), "1.005", "USD"))
                    .isEqualTo(new FxConversion(Money.of(302, "USD"), "-0.5"));
        }

        @Test
        void justBelowAndJustAboveTheTieRoundNormallyAndKeepTheirRemainder() {
            FxConversion below = FxMath.convertWithResidual(Money.of(100, "EUR"), "1.00499", "USD");
            FxConversion above = FxMath.convertWithResidual(Money.of(100, "EUR"), "1.00501", "USD");

            assertThat(below.converted()).isEqualTo(Money.of(100, "USD"));
            assertThat(below.residualMinorFraction()).isEqualTo("0.499");
            assertThat(below.exactMinorUnits()).isEqualTo("100.499");

            assertThat(above.converted()).isEqualTo(Money.of(101, "USD"));
            assertThat(above.residualMinorFraction()).isEqualTo("-0.499");
            assertThat(above.exactMinorUnits()).isEqualTo("100.501");
        }

        @Test
        void negativeAmountsRoundSymmetricallyAndTheirResidualKeepsItsSign() {
            // -1.01500 -> -101.5 cents -> -102, again because 2 is the even neighbour.
            FxConversion result = FxMath.convertWithResidual(Money.of(-100, "EUR"), "1.015", "USD");

            assertThat(result.converted()).isEqualTo(Money.of(-102, "USD"));
            assertThat(result.residualMinorFraction()).isEqualTo("0.5");
            assertThat(result.exactMinorUnits()).isEqualTo("-101.5");
        }
    }

    @Nested
    @DisplayName("the residual is always accounted for")
    class TheResidualIsAlwaysAccountedFor {

        @Test
        void anExactConversionLeavesNoResidualAtAll() {
            FxConversion result = FxMath.convertWithResidual(Money.of(10_000, "EUR"), "1.08", "USD");

            assertThat(result.converted()).isEqualTo(Money.of(10_800, "USD"));
            assertThat(result.isExact()).isTrue();
            assertThat(result.residualMinorFraction()).isEqualTo("0");
            assertThat(result.exactMinorUnits()).isEqualTo("10800");
        }

        @Test
        void aRateAtFullStoredScaleStillConvertsExactlyWhenItDividesEvenly() {
            // numeric(20,10) is how every rate is persisted, so trailing zeros at scale 10 are the
            // normal case, not an edge one - they must not show up as a phantom residual.
            FxConversion result = FxMath.convertWithResidual(Money.of(10_000, "EUR"), "0.8600000000", "GBP");

            assertThat(result.converted()).isEqualTo(Money.of(8_600, "GBP"));
            assertThat(result.isExact()).isTrue();
            assertThat(result.residualMinorFraction()).isEqualTo("0");
        }

        @Test
        void thePhasesWorkedExampleAtTheClientRateIsExact() {
            // The phase's own numbers: Alice sells 100.00 EUR at a client rate of 0.8543 and gets
            // 85.43 GBP - the user leg of the five-leg entry, and it happens to be exact.
            FxConversion result = FxMath.convertWithResidual(Money.of(10_000, "EUR"), "0.8543", "GBP");

            assertThat(result.converted()).isEqualTo(Money.of(8_543, "GBP"));
            assertThat(result.isExact()).isTrue();
        }

        @Test
        void aClientRateWithOneDigitTooManyLandsOnATieAndKeepsTheHalfPenny() {
            // Apply 50 bps to a mid that is not a round number and the client rate gains a digit
            // the target currency cannot hold. That half-penny is what ends up in FX_PNL.
            FxConversion result = FxMath.convertWithResidual(Money.of(10_000, "EUR"), "0.85435", "GBP");

            assertThat(result.converted()).isEqualTo(Money.of(8_544, "GBP"));
            assertThat(result.residualMinorFraction()).isEqualTo("-0.5");
            assertThat(result.exactMinorUnits()).isEqualTo("8543.5");
        }

        @Test
        void aLongResidualIsKeptToItsFullPrecisionRatherThanRoundedAgain() {
            // 100.01 EUR * 0.8543007 = 85.438613007 GBP exactly; the residual is that whole tail,
            // not a rounded approximation of it, or the sixth leg would be a guess after all.
            FxConversion result = FxMath.convertWithResidual(Money.of(10_001, "EUR"), "0.8543007", "GBP");

            assertThat(result.converted()).isEqualTo(Money.of(8_544, "GBP"));
            assertThat(result.exactMinorUnits()).isEqualTo("8543.8613007");
            assertThat(result.residualMinorFraction()).isEqualTo("-0.1386993");
        }

        @Test
        void convertingZeroIsExactAtAnyRate() {
            FxConversion result = FxMath.convertWithResidual(Money.zero("EUR"), "0.85435", "GBP");

            assertThat(result.converted()).isEqualTo(Money.zero("GBP"));
            assertThat(result.isExact()).isTrue();
            assertThat(result.exactMinorUnits()).isEqualTo("0");
        }

        @Test
        void anAmountSmallerThanOneMinorUnitOfTheTargetIsCarriedEntirelyByTheResidual() {
            // Nothing is invented and nothing vanishes: the conversion posts zero and the residual
            // holds the full value, so the caller can still account for it.
            FxConversion result = FxMath.convertWithResidual(Money.of(1, "EUR"), "0.3", "GBP");

            assertThat(result.converted()).isEqualTo(Money.zero("GBP"));
            assertThat(result.residualMinorFraction()).isEqualTo("0.3");
            assertThat(result.exactMinorUnits()).isEqualTo("0.3");
        }
    }

    @Nested
    @DisplayName("rejected input")
    class RejectedInput {

        @Test
        void aNullAmount() {
            assertThatThrownBy(() -> FxMath.convertWithResidual(null, "1.08", "USD"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void aNullRate() {
            assertThatThrownBy(() -> FxMath.convertWithResidual(Money.of(100, "EUR"), null, "USD"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void aNullTargetCurrency() {
            assertThatThrownBy(() -> FxMath.convertWithResidual(Money.of(100, "EUR"), "1.08", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void aZeroRate() {
            assertThatThrownBy(() -> FxMath.convertWithResidual(Money.of(100, "EUR"), "0", "USD"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aNegativeRate() {
            assertThatThrownBy(() -> FxMath.convertWithResidual(Money.of(100, "EUR"), "-1.08", "USD"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aRateThatIsNotANumber() {
            assertThatThrownBy(() -> FxMath.convertWithResidual(Money.of(100, "EUR"), "0.86 GBP", "USD"))
                    .isInstanceOf(NumberFormatException.class);
        }

        @Test
        void anUnknownTargetCurrency() {
            assertThatThrownBy(() -> FxMath.convertWithResidual(Money.of(100, "EUR"), "1.08", "NOPE"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
