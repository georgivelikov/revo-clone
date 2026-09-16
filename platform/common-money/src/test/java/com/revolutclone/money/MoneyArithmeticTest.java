package com.revolutclone.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * plus / minus / negate / abs, zero handling, and the 0-digit currency.
 *
 * <p>Deliberately free of any decimal type: everything here is expressed in minor units,
 * which is how the rest of the codebase is required to talk about money.
 */
class MoneyArithmeticTest {

    @Nested
    @DisplayName("plus")
    class Plus {

        @Test
        void addsTwoPositiveAmounts() {
            assertThat(Money.of(1234, "EUR").plus(Money.of(66, "EUR")))
                    .isEqualTo(Money.of(1300, "EUR"));
        }

        @Test
        void addingANegativeAmountSubtracts() {
            assertThat(Money.of(1234, "EUR").plus(Money.of(-234, "EUR")))
                    .isEqualTo(Money.of(1000, "EUR"));
        }

        @Test
        void addingZeroIsIdentity() {
            Money ten = Money.of(1000, "GBP");
            assertThat(ten.plus(Money.zero("GBP"))).isEqualTo(ten);
        }

        @Test
        void overflowThrowsRatherThanWrappingAround() {
            assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "EUR").plus(Money.of(1, "EUR")))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    @DisplayName("minus")
    class Minus {

        @Test
        void subtractsWithinTheSameCurrency() {
            assertThat(Money.of(1300, "USD").minus(Money.of(66, "USD")))
                    .isEqualTo(Money.of(1234, "USD"));
        }

        @Test
        void producesANegativeAmountWhenTheSubtrahendIsLarger() {
            assertThat(Money.of(100, "USD").minus(Money.of(250, "USD")))
                    .isEqualTo(Money.of(-150, "USD"));
        }

        @Test
        void subtractingItselfYieldsZero() {
            Money amount = Money.of(4711, "USD");
            assertThat(amount.minus(amount).isZero()).isTrue();
        }

        @Test
        void overflowThrowsRatherThanWrappingAround() {
            assertThatThrownBy(() -> Money.of(Long.MIN_VALUE, "EUR").minus(Money.of(1, "EUR")))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    @DisplayName("negate and abs")
    class NegateAndAbs {

        @Test
        void negateFlipsTheSign() {
            assertThat(Money.of(1234, "EUR").negate()).isEqualTo(Money.of(-1234, "EUR"));
            assertThat(Money.of(-1234, "EUR").negate()).isEqualTo(Money.of(1234, "EUR"));
        }

        @Test
        void negateOfZeroIsZero() {
            assertThat(Money.zero("EUR").negate()).isEqualTo(Money.zero("EUR"));
        }

        @Test
        void negateKeepsTheCurrency() {
            assertThat(Money.of(500, "JPY").negate().currency().getCurrencyCode()).isEqualTo("JPY");
        }

        @Test
        void absIsAlwaysNonNegative() {
            assertThat(Money.of(-1234, "EUR").abs()).isEqualTo(Money.of(1234, "EUR"));
            assertThat(Money.of(1234, "EUR").abs()).isEqualTo(Money.of(1234, "EUR"));
            assertThat(Money.zero("EUR").abs()).isEqualTo(Money.zero("EUR"));
        }

        @Test
        void absOfTheMostNegativeValueThrowsRatherThanStayingNegative() {
            assertThatThrownBy(() -> Money.of(Long.MIN_VALUE, "EUR").abs())
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    @DisplayName("sign predicates")
    class SignPredicates {

        @Test
        void zeroIsNeitherPositiveNorNegative() {
            Money zero = Money.zero("EUR");
            assertThat(zero.isZero()).isTrue();
            assertThat(zero.isPositive()).isFalse();
            assertThat(zero.isNegative()).isFalse();
        }

        @Test
        void aPositiveAmountIsOnlyPositive() {
            Money one = Money.of(1, "EUR");
            assertThat(one.isZero()).isFalse();
            assertThat(one.isPositive()).isTrue();
            assertThat(one.isNegative()).isFalse();
        }

        @Test
        void aNegativeAmountIsOnlyNegative() {
            Money minusOne = Money.of(-1, "EUR");
            assertThat(minusOne.isZero()).isFalse();
            assertThat(minusOne.isPositive()).isFalse();
            assertThat(minusOne.isNegative()).isTrue();
        }
    }

    @Nested
    @DisplayName("0-digit currencies")
    class ZeroDigitCurrencies {

        @Test
        void jpyHasNoMinorUnitSoMinorUnitsAreWholeYen() {
            assertThat(Money.of(1000, "JPY").currency().getDefaultFractionDigits()).isZero();
        }

        @Test
        void jpyArithmeticIsWholeYen() {
            assertThat(Money.of(1000, "JPY").plus(Money.of(500, "JPY")))
                    .isEqualTo(Money.of(1500, "JPY"));
        }

        @Test
        void jpyRendersWithoutADecimalPoint() {
            assertThat(Money.of(1500, "JPY")).hasToString("1500 JPY");
        }

        @Test
        void twoDigitCurrenciesRenderWithTwoDecimals() {
            assertThat(Money.of(1234, "EUR")).hasToString("12.34 EUR");
            assertThat(Money.of(5, "EUR")).hasToString("0.05 EUR");
            assertThat(Money.of(-1234, "EUR")).hasToString("-12.34 EUR");
        }
    }

    @Nested
    @DisplayName("construction and identity")
    class ConstructionAndIdentity {

        @Test
        void zeroIsZeroInTheRequestedCurrency() {
            Money zero = Money.zero("GBP");
            assertThat(zero.minor()).isZero();
            assertThat(zero.currency().getCurrencyCode()).isEqualTo("GBP");
        }

        @Test
        void equalAmountsInTheSameCurrencyAreEqual() {
            assertThat(Money.of(1234, "EUR"))
                    .isEqualTo(Money.of(1234, "EUR"))
                    .hasSameHashCodeAs(Money.of(1234, "EUR"));
        }

        @Test
        void equalAmountsInDifferentCurrenciesAreNotEqual() {
            assertThat(Money.of(1234, "EUR")).isNotEqualTo(Money.of(1234, "USD"));
        }

        @Test
        void comparesByAmountWithinACurrency() {
            assertThat(Money.of(100, "EUR")).isLessThan(Money.of(200, "EUR"));
            assertThat(Money.of(200, "EUR")).isGreaterThan(Money.of(100, "EUR"));
            assertThat(Money.of(100, "EUR")).isEqualByComparingTo(Money.of(100, "EUR"));
        }

        @Test
        void anUnknownCurrencyCodeIsRejected() {
            assertThatThrownBy(() -> Money.of(1, "NOPE"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aCurrencyWithNoDefinedMinorUnitIsRejected() {
            // XAU (gold) is an ISO code with no minor unit. It is not money we can post.
            assertThatThrownBy(() -> Money.of(1, "XAU"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("XAU");
        }
    }
}
