package com.revolutclone.money;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FxMath#convert} is the one place in the project where a rate is applied, so it is
 * also the one place a half-cent can be created or destroyed. HALF_EVEN is the rounding
 * mode precisely because it has no systematic bias: over many conversions the ties cancel
 * instead of accumulating in the bank's favour, which is what HALF_UP would do.
 */
class FxMathTest {

    @Nested
    @DisplayName("HALF_EVEN at the boundary")
    class HalfEvenAtTheBoundary {

        @Test
        void anExactHalfRoundsDownWhenThatLeavesAnEvenLastDigit() {
            // 1.00 EUR * 1.005 = 1.00500 exactly -> tie -> 1.00, because 0 is even.
            assertThat(FxMath.convert(Money.of(100, "EUR"), new BigDecimal("1.005"), "USD"))
                    .isEqualTo(Money.of(100, "USD"));
        }

        @Test
        void anExactHalfRoundsUpWhenThatLeavesAnEvenLastDigit() {
            // 1.00 EUR * 1.015 = 1.01500 exactly -> tie -> 1.02, because 2 is even.
            assertThat(FxMath.convert(Money.of(100, "EUR"), new BigDecimal("1.015"), "USD"))
                    .isEqualTo(Money.of(102, "USD"));
        }

        @Test
        void theTwoDirectionsAreNotTheSameAsAlwaysRoundingUp() {
            Money from = Money.of(100, "EUR");
            Money down = FxMath.convert(from, new BigDecimal("1.005"), "USD");
            Money up = FxMath.convert(from, new BigDecimal("1.015"), "USD");

            assertThat(down.minor()).isEqualTo(100);
            assertThat(up.minor()).isEqualTo(102);
        }

        @Test
        void justBelowAndJustAboveTheTieRoundNormally() {
            assertThat(FxMath.convert(Money.of(100, "EUR"), new BigDecimal("1.00499"), "USD"))
                    .isEqualTo(Money.of(100, "USD"));
            assertThat(FxMath.convert(Money.of(100, "EUR"), new BigDecimal("1.00501"), "USD"))
                    .isEqualTo(Money.of(101, "USD"));
        }

        @Test
        void negativeAmountsRoundSymmetrically() {
            assertThat(FxMath.convert(Money.of(-100, "EUR"), new BigDecimal("1.015"), "USD"))
                    .isEqualTo(Money.of(-102, "USD"));
        }
    }

    @Nested
    @DisplayName("converting to a 0-digit currency")
    class ZeroDigitTarget {

        @Test
        void scalesToWholeUnitsNotToTwoDecimals() {
            // 1.00 EUR * 160.42 = 160.42 JPY -> 160 yen, not 16042 of anything.
            assertThat(FxMath.convert(Money.of(100, "EUR"), new BigDecimal("160.42"), "JPY"))
                    .isEqualTo(Money.of(160, "JPY"));
        }

        @Test
        void appliesHalfEvenAtTheWholeUnitBoundary() {
            // 160.5 -> 160 (even) and 161.5 -> 162 (even).
            assertThat(FxMath.convert(Money.of(100, "EUR"), new BigDecimal("160.5"), "JPY"))
                    .isEqualTo(Money.of(160, "JPY"));
            assertThat(FxMath.convert(Money.of(100, "EUR"), new BigDecimal("161.5"), "JPY"))
                    .isEqualTo(Money.of(162, "JPY"));
        }

        @Test
        void convertsBackOutOfAZeroDigitCurrency() {
            // 1000 JPY * 0.00625 = 6.25 EUR.
            assertThat(FxMath.convert(Money.of(1000, "JPY"), new BigDecimal("0.00625"), "EUR"))
                    .isEqualTo(Money.of(625, "EUR"));
        }

        @Test
        void aSubUnitResultRoundsToZeroRatherThanFailing() {
            assertThat(FxMath.convert(Money.of(1, "EUR"), new BigDecimal("0.4"), "JPY"))
                    .isEqualTo(Money.zero("JPY"));
        }
    }

    @Nested
    @DisplayName("ordinary conversions")
    class OrdinaryConversions {

        @Test
        void appliesTheRateAndAdoptsTheTargetCurrency() {
            Money converted = FxMath.convert(Money.of(10_000, "EUR"), new BigDecimal("1.08"), "USD");

            assertThat(converted.minor()).isEqualTo(10_800);
            assertThat(converted.currency().getCurrencyCode()).isEqualTo("USD");
        }

        @Test
        void aRateOfOneIsValuePreserving() {
            assertThat(FxMath.convert(Money.of(1234, "EUR"), BigDecimal.ONE, "USD"))
                    .isEqualTo(Money.of(1234, "USD"));
        }

        @Test
        void convertingZeroYieldsZeroInTheTargetCurrency() {
            assertThat(FxMath.convert(Money.zero("EUR"), new BigDecimal("1.08"), "USD"))
                    .isEqualTo(Money.zero("USD"));
        }

        @Test
        void convertingToTheSameCurrencyIsAllowed() {
            assertThat(FxMath.convert(Money.of(100, "EUR"), new BigDecimal("1.05"), "EUR"))
                    .isEqualTo(Money.of(105, "EUR"));
        }
    }

    @Nested
    @DisplayName("rejected input")
    class RejectedInput {

        @Test
        void aNullAmount() {
            assertThatThrownBy(() -> FxMath.convert(null, BigDecimal.ONE, "USD"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void aNullRate() {
            assertThatThrownBy(() -> FxMath.convert(Money.of(100, "EUR"), null, "USD"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void aZeroRate() {
            assertThatThrownBy(() -> FxMath.convert(Money.of(100, "EUR"), BigDecimal.ZERO, "USD"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aNegativeRate() {
            assertThatThrownBy(() -> FxMath.convert(Money.of(100, "EUR"), new BigDecimal("-1.08"), "USD"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void anUnknownTargetCurrency() {
            assertThatThrownBy(() -> FxMath.convert(Money.of(100, "EUR"), BigDecimal.ONE, "NOPE"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("random walk step")
    class RandomWalkStep {

        @Test
        void aPositiveNoiseSampleMovesTheMidUp() {
            String next = FxMath.randomWalkStep("0.86000000", "0.0005", "2.0");

            assertThat(new BigDecimal(next)).isGreaterThan(new BigDecimal("0.86000000"));
        }

        @Test
        void aNegativeNoiseSampleMovesTheMidDown() {
            String next = FxMath.randomWalkStep("0.86000000", "0.0005", "-2.0");

            assertThat(new BigDecimal(next)).isLessThan(new BigDecimal("0.86000000"));
        }

        @Test
        void isDeterministicForTheSameInputs() {
            String first = FxMath.randomWalkStep("1.08000000", "0.0005", "0.37");
            String second = FxMath.randomWalkStep("1.08000000", "0.0005", "0.37");

            assertThat(first).isEqualTo(second);
        }

        @Test
        void clampsToAPositiveFloorRatherThanGoingToZeroOrNegative() {
            // A wildly negative noise sample would otherwise drive the mid below zero.
            String next = FxMath.randomWalkStep("0.86000000", "50", "-10.0");

            assertThat(new BigDecimal(next).signum()).isPositive();
        }

        @Test
        void rejectsNegativeVolatility() {
            assertThatThrownBy(() -> FxMath.randomWalkStep("0.86000000", "-0.1", "1.0"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("inverting a rate")
    class InvertingARate {

        @Test
        void invertsToTenDecimalPlaces() {
            assertThat(FxMath.invert("0.8600000000")).isEqualTo("1.1627906977");
        }

        @Test
        void invertingTwiceRoundTripsWithinRoundingTolerance() {
            String mid = "0.8600000000";
            String roundTripped = FxMath.invert(FxMath.invert(mid));

            // Two HALF_EVEN roundings at scale 10 can only ever differ by a unit in the last
            // place - nowhere near the size of any spread this gets composed with downstream.
            assertThat(new BigDecimal(roundTripped).subtract(new BigDecimal(mid)).abs())
                    .isLessThanOrEqualTo(new BigDecimal("0.0000000001"));
        }

        @Test
        void theProductOfARateAndItsInverseIsOne() {
            String mid = "1.08000000";
            BigDecimal product = new BigDecimal(mid).multiply(new BigDecimal(FxMath.invert(mid)));

            assertThat(product.subtract(BigDecimal.ONE).abs()).isLessThan(new BigDecimal("0.0000000001"));
        }

        @Test
        void rejectsZero() {
            assertThatThrownBy(() -> FxMath.invert("0"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNegative() {
            assertThatThrownBy(() -> FxMath.invert("-0.86"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("applying a spread to a mid rate")
    class ApplyingASpread {

        @Test
        void fiftyBasisPointsIsHalfAPercentOffTheMid() {
            assertThat(FxMath.applySpread("0.8600000000", 50)).isEqualTo("0.8557000000");
        }

        @Test
        void theClientRateIsAlwaysTheWorseOfTheTwoForTheClient() {
            assertThat(new BigDecimal(FxMath.applySpread("1.0800000000", 50)))
                    .isLessThan(new BigDecimal("1.0800000000"));
        }

        @Test
        void aZeroSpreadIsTheMidRateItself() {
            assertThat(FxMath.applySpread("0.8600000000", 0)).isEqualTo("0.8600000000");
        }

        @Test
        void theResultIsAlwaysScaledToTheRateColumnsTenDigits() {
            // 162.5 JPY per EUR at 50 bps is 161.6875, which would otherwise carry four digits.
            assertThat(FxMath.applySpread("162.5", 50)).isEqualTo("161.6875000000");
        }

        @Test
        void rejectsANonPositiveMid() {
            assertThatThrownBy(() -> FxMath.applySpread("0", 50)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> FxMath.applySpread("-0.86", 50)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsASpreadThatWouldBeNegativeOrWipeOutTheRate() {
            assertThatThrownBy(() -> FxMath.applySpread("0.86", -1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> FxMath.applySpread("0.86", 10_000))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void theSpreadRateAndTheClientRateAddBackUpToTheMidExactly() {
            // The whole point of deriving the fee rate by subtraction: the three buy-side legs of
            // an FX entry are computed from these two rates, so any inexactness here would land in
            // FEE_INCOME as drift rather than in FX_PNL as a residual.
            String mid = "0.8637123456";
            String clientRate = FxMath.applySpread(mid, 50);

            assertThat(new BigDecimal(clientRate).add(new BigDecimal(FxMath.spreadRate(mid, clientRate))))
                    .isEqualByComparingTo(new BigDecimal(mid));
        }

        @Test
        void aZeroSpreadHasAZeroFeeRate() {
            String mid = "0.8600000000";

            assertThat(new BigDecimal(FxMath.spreadRate(mid, FxMath.applySpread(mid, 0)))).isEqualByComparingTo("0");
        }

        @Test
        void aClientRateBetterThanTheMidIsRejectedRatherThanPaidOut() {
            assertThatThrownBy(() -> FxMath.spreadRate("0.8600000000", "0.8700000000"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
