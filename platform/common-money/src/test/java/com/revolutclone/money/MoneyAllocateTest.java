package com.revolutclone.money;

import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The single easiest place in the project to lose a cent, so it gets a property test as
 * well as worked examples.
 */
class MoneyAllocateTest {

    @Nested
    @DisplayName("worked examples")
    class WorkedExamples {

        @Test
        void oneHundredSplitThreeWaysIsThirtyFourThirtyThreeThirtyThree() {
            assertThat(Money.of(100, "EUR").allocate(1, 1, 1))
                    .containsExactly(Money.of(34, "EUR"), Money.of(33, "EUR"), Money.of(33, "EUR"));
        }

        @Test
        void theRemainderGoesToTheEarliestLargestFraction() {
            // 5 across three equal shares: 1.66r each, so two units are handed out.
            assertThat(Money.of(5, "EUR").allocate(1, 1, 1))
                    .containsExactly(Money.of(2, "EUR"), Money.of(2, "EUR"), Money.of(1, "EUR"));
        }

        @Test
        void weightedRatiosDivideProportionally() {
            assertThat(Money.of(10000, "EUR").allocate(1, 1, 2))
                    .containsExactly(Money.of(2500, "EUR"), Money.of(2500, "EUR"), Money.of(5000, "EUR"));
        }

        @Test
        void weightedRatiosGiveTheRemainderToTheLargestFractionNotTheLargestRatio() {
            // 10 at 3:2:2 -> exact shares 4.285..., 2.857..., 2.857...
            // Truncation gives 4, 2, 2 with 2 units left over; the two largest fractional
            // parts (0.857) are the second and third share, not the first.
            assertThat(Money.of(10, "EUR").allocate(3, 2, 2))
                    .containsExactly(Money.of(4, "EUR"), Money.of(3, "EUR"), Money.of(3, "EUR"));
        }

        @Test
        void aSingleRatioTakesTheWholeAmount() {
            assertThat(Money.of(999, "EUR").allocate(7)).containsExactly(Money.of(999, "EUR"));
        }

        @Test
        void aZeroRatioGetsNothing() {
            assertThat(Money.of(100, "EUR").allocate(1, 0, 1))
                    .containsExactly(Money.of(50, "EUR"), Money.zero("EUR"), Money.of(50, "EUR"));
        }

        @Test
        void zeroAllocatesToAllZeroes() {
            assertThat(Money.zero("EUR").allocate(1, 2, 3))
                    .containsExactly(Money.zero("EUR"), Money.zero("EUR"), Money.zero("EUR"));
        }

        @Test
        void allocationHappensInWholeMinorUnitsOfAZeroDigitCurrency() {
            assertThat(Money.of(100, "JPY").allocate(1, 1, 1))
                    .containsExactly(Money.of(34, "JPY"), Money.of(33, "JPY"), Money.of(33, "JPY"));
        }

        @Test
        void everyPartKeepsTheCurrency() {
            assertThat(Money.of(100, "JPY").allocate(1, 1, 1))
                    .allSatisfy(part -> assertThat(part.currency().getCurrencyCode()).isEqualTo("JPY"));
        }
    }

    @Nested
    @DisplayName("negative amounts")
    class NegativeAmounts {

        @Test
        void aNegativeAmountMirrorsThePositiveSplit() {
            assertThat(Money.of(-100, "EUR").allocate(1, 1, 1))
                    .containsExactly(Money.of(-34, "EUR"), Money.of(-33, "EUR"), Money.of(-33, "EUR"));
        }

        @Test
        void aNegativeAmountLosesNothingEither() {
            Money[] parts = Money.of(-101, "EUR").allocate(1, 1, 1);
            assertThat(sumOf(parts)).isEqualTo(-101);
        }
    }

    @Nested
    @DisplayName("rejected input")
    class RejectedInput {

        @Test
        void noRatiosAtAll() {
            assertThatThrownBy(() -> Money.of(100, "EUR").allocate())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aNegativeRatio() {
            assertThatThrownBy(() -> Money.of(100, "EUR").allocate(1, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void ratiosThatSumToZero() {
            assertThatThrownBy(() -> Money.of(100, "EUR").allocate(0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("properties over random amounts and ratios")
    class Properties {

        private static final String[] CURRENCIES = {"EUR", "USD", "GBP", "JPY"};
        private static final int ROUNDS = 5_000;

        /** Fixed seed: a failure here must be reproducible, not a once-a-week mystery. */
        private static Random seededRandom() {
            return new Random(20260913L);
        }

        @Test
        void theSumOfThePartsAlwaysEqualsTheInput() {
            Random random = seededRandom();
            for (int round = 0; round < ROUNDS; round++) {
                long amount = randomAmount(random);
                int[] ratios = randomRatios(random);
                String code = CURRENCIES[random.nextInt(CURRENCIES.length)];

                Money[] parts = Money.of(amount, code).allocate(ratios);

                assertThat(sumOf(parts))
                        .withFailMessage("lost units allocating %d %s across %s -> %s",
                                amount, code, java.util.Arrays.toString(ratios),
                                java.util.Arrays.toString(parts))
                        .isEqualTo(amount);
            }
        }

        @Test
        void everyPartIsWithinOneUnitOfItsExactShare() {
            Random random = seededRandom();
            for (int round = 0; round < ROUNDS; round++) {
                long amount = randomAmount(random);
                int[] ratios = randomRatios(random);
                long ratioSum = 0;
                for (int ratio : ratios) {
                    ratioSum += ratio;
                }

                Money[] parts = Money.of(amount, "EUR").allocate(ratios);

                for (int i = 0; i < parts.length; i++) {
                    // |part * ratioSum - amount * ratio| < ratioSum  <=>  the part is
                    // within one minor unit of the exact share. Stated this way it needs
                    // no division and therefore no rounding of its own.
                    long error = Math.abs(parts[i].minor() * ratioSum - amount * ratios[i]);
                    assertThat(error)
                            .withFailMessage("part %d of %d %s across %s drifted by %d/%d units",
                                    i, amount, "EUR", java.util.Arrays.toString(ratios),
                                    error, ratioSum)
                            .isLessThan(ratioSum);
                }
            }
        }

        @Test
        void aLargerRatioNeverReceivesLessThanASmallerOne() {
            Random random = seededRandom();
            for (int round = 0; round < ROUNDS; round++) {
                long amount = Math.abs(randomAmount(random));
                int[] ratios = randomRatios(random);

                Money[] parts = Money.of(amount, "EUR").allocate(ratios);

                for (int i = 0; i < ratios.length; i++) {
                    for (int j = 0; j < ratios.length; j++) {
                        if (ratios[i] > ratios[j]) {
                            assertThat(parts[i].minor())
                                    .withFailMessage("ratio %d got %d but smaller ratio %d got %d",
                                            ratios[i], parts[i].minor(), ratios[j], parts[j].minor())
                                    .isGreaterThanOrEqualTo(parts[j].minor());
                        }
                    }
                }
            }
        }

        /** Amounts small enough that `amount * ratioSum` in the assertions stays exact. */
        private static long randomAmount(Random random) {
            return random.nextLong(-1_000_000_000L, 1_000_000_000L);
        }

        private static int[] randomRatios(Random random) {
            int[] ratios = new int[1 + random.nextInt(8)];
            boolean anyPositive = false;
            for (int i = 0; i < ratios.length; i++) {
                ratios[i] = random.nextInt(101);
                anyPositive |= ratios[i] > 0;
            }
            if (!anyPositive) {
                ratios[random.nextInt(ratios.length)] = 1 + random.nextInt(100);
            }
            return ratios;
        }
    }

    private static long sumOf(Money[] parts) {
        long total = 0;
        for (Money part : parts) {
            total += part.minor();
        }
        return total;
    }
}
