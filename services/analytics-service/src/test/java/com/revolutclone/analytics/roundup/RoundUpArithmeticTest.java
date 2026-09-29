package com.revolutclone.analytics.roundup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The pure formula in isolation, without the Spring context {@link RoundUpTest} needs to prove it
 * end to end - a plain unit test is enough here since {@link RoundUp} is a pure function of two
 * primitives, no {@code double}/{@code float} anywhere (CLAUDE.md hard rule 1).
 */
class RoundUpArithmeticTest {

    @Test
    void roundsUpToTheNextWholeEuro() {
        assertThat(RoundUp.compute(437L, "EUR")).isEqualTo(63L);
    }

    @Test
    void anAlreadyWholeAmountRoundsUpToZero() {
        assertThat(RoundUp.compute(500L, "EUR")).isEqualTo(0L);
    }

    @Test
    void aOneCentAmountRoundsUpToTheWholeUnit() {
        assertThat(RoundUp.compute(401L, "EUR")).isEqualTo(99L);
    }

    @Test
    void zeroDecimalCurrenciesNeverRoundUpSinceEveryAmountIsAlreadyAWholeUnit() {
        assertThat(RoundUp.compute(437L, "JPY")).isEqualTo(0L);
        assertThat(RoundUp.compute(1L, "JPY")).isEqualTo(0L);
    }

    @Test
    void aNonPositiveSettlementNeverRoundsUp() {
        assertThat(RoundUp.compute(0L, "EUR")).isEqualTo(0L);
        assertThat(RoundUp.compute(-437L, "EUR")).isEqualTo(0L);
    }
}
