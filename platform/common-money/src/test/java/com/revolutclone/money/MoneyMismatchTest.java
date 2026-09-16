package com.revolutclone.money;

import java.util.Currency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every binary operation refuses to mix currencies. There is no implicit conversion
 * anywhere in this project: crossing currencies is always an explicit FX operation with a
 * rate, and a silent one would be a rounding bug hiding as a feature.
 */
class MoneyMismatchTest {

    private static final Money EUR = Money.of(1000, "EUR");
    private static final Money USD = Money.of(1000, "USD");

    @Test
    void plusThrowsOnMismatchedCurrency() {
        assertThatThrownBy(() -> EUR.plus(USD)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void minusThrowsOnMismatchedCurrency() {
        assertThatThrownBy(() -> EUR.minus(USD)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void compareToThrowsOnMismatchedCurrency() {
        assertThatThrownBy(() -> EUR.compareTo(USD)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void mismatchIsSymmetric() {
        assertThatThrownBy(() -> USD.plus(EUR)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> USD.minus(EUR)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> USD.compareTo(EUR)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void zeroIsNotAFreePassAcrossCurrencies() {
        // A zero amount is still an amount in a currency; letting it through would make
        // "empty" balances silently absorbable into any other currency.
        assertThatThrownBy(() -> EUR.plus(Money.zero("USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void theExceptionCarriesBothCurrencies() {
        CurrencyMismatchException thrown = catchMismatch(() -> EUR.plus(USD));

        assertThat(thrown.left()).isEqualTo(Currency.getInstance("EUR"));
        assertThat(thrown.right()).isEqualTo(Currency.getInstance("USD"));
        assertThat(thrown).hasMessageContaining("EUR").hasMessageContaining("USD");
    }

    @Test
    void matchingCurrenciesDoNotThrow() {
        assertThatCode(() -> {
            EUR.plus(Money.of(1, "EUR"));
            EUR.minus(Money.of(1, "EUR"));
            EUR.compareTo(Money.of(1, "EUR"));
        }).doesNotThrowAnyException();
    }

    @Test
    void mismatchIsUncheckedSoBusinessCodeNeedNotDeclareIt() {
        assertThat(RuntimeException.class).isAssignableFrom(CurrencyMismatchException.class);
    }

    private static CurrencyMismatchException catchMismatch(Runnable operation) {
        try {
            operation.run();
        } catch (CurrencyMismatchException e) {
            return e;
        }
        throw new AssertionError("expected a CurrencyMismatchException");
    }
}
