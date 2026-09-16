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
}
