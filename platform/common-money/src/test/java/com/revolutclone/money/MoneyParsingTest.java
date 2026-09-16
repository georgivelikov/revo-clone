package com.revolutclone.money;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ofMajor} / {@code toMajor} — the only doorway between decimal notation and the
 * minor-unit representation everything else uses.
 *
 * <p>The point of the class under test is that the doorway is <em>exact</em>. An amount
 * that cannot be represented in the currency's minor unit is rejected, never rounded: a
 * silent round here would be a real cent going missing at the edge of the system, which is
 * exactly the class of bug the whole long-minor-units rule exists to prevent.
 */
class MoneyParsingTest {

    @Nested
    @DisplayName("ofMajor")
    class OfMajor {

        @Test
        void readsATwoDecimalAmount() {
            assertThat(Money.ofMajor(new BigDecimal("12.34"), "EUR")).isEqualTo(Money.of(1234, "EUR"));
        }

        @Test
        void readsAnAmountWithFewerDecimalsThanTheCurrencyAllows() {
            assertThat(Money.ofMajor(new BigDecimal("12.3"), "EUR")).isEqualTo(Money.of(1230, "EUR"));
            assertThat(Money.ofMajor(new BigDecimal("12"), "EUR")).isEqualTo(Money.of(1200, "EUR"));
        }

        @Test
        void readsANegativeAmount() {
            assertThat(Money.ofMajor(new BigDecimal("-12.34"), "EUR")).isEqualTo(Money.of(-1234, "EUR"));
        }

        @Test
        void readsZero() {
            assertThat(Money.ofMajor(BigDecimal.ZERO, "EUR")).isEqualTo(Money.zero("EUR"));
        }

        @Test
        void rejectsExcessScaleRatherThanRounding() {
            assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("1.005"), "EUR"))
                    .isInstanceOf(ArithmeticException.class)
                    .hasMessageContaining("1.005")
                    .hasMessageContaining("EUR");
        }

        @Test
        void rejectsExcessScaleEvenWhenTheRoundingWouldBeObvious() {
            assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("0.001"), "EUR"))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        void acceptsTrailingZeroesBecauseTheValueIsStillExact() {
            // 1.5000 EUR is exactly 150 cents. It is the *value* that must be
            // representable, not the incidental scale the caller happened to carry.
            assertThat(Money.ofMajor(new BigDecimal("1.5000"), "EUR")).isEqualTo(Money.of(150, "EUR"));
        }

        @Test
        void aZeroDigitCurrencyRejectsAnyFraction() {
            assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("10.5"), "JPY"))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        void aZeroDigitCurrencyReadsWholeUnits() {
            assertThat(Money.ofMajor(new BigDecimal("1000"), "JPY")).isEqualTo(Money.of(1000, "JPY"));
        }

        @Test
        void rejectsAnAmountTooLargeForTheMinorUnitRepresentation() {
            assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("1E+30"), "EUR"))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        void rejectsAnUnknownCurrencyCode() {
            assertThatThrownBy(() -> Money.ofMajor(BigDecimal.ONE, "NOPE"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsANullAmount() {
            assertThatThrownBy(() -> Money.ofMajor(null, "EUR"))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("toMajor")
    class ToMajor {

        @Test
        void scalesToTheCurrencyFractionDigits() {
            assertThat(Money.of(1234, "EUR").toMajor()).isEqualByComparingTo("12.34");
            assertThat(Money.of(1234, "EUR").toMajor().scale()).isEqualTo(2);
        }

        @Test
        void aZeroDigitCurrencyHasScaleZero() {
            assertThat(Money.of(1000, "JPY").toMajor()).isEqualByComparingTo("1000");
            assertThat(Money.of(1000, "JPY").toMajor().scale()).isZero();
        }

        @Test
        void keepsTheSign() {
            assertThat(Money.of(-5, "EUR").toMajor()).isEqualByComparingTo("-0.05");
        }

        @Test
        void roundTripsThroughOfMajorForEveryScale() {
            for (long minor : new long[] {0, 1, -1, 5, 99, 100, 101, -1234, 123_456_789}) {
                Money original = Money.of(minor, "EUR");
                assertThat(Money.ofMajor(original.toMajor(), "EUR")).isEqualTo(original);
            }
        }

        @Test
        void roundTripsForAZeroDigitCurrency() {
            Money original = Money.of(123_456, "JPY");
            assertThat(Money.ofMajor(original.toMajor(), "JPY")).isEqualTo(original);
        }
    }
}
