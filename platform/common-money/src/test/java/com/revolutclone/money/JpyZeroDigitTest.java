package com.revolutclone.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.revolutclone.money.FxMath.FxConversion;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPY is in plans/phase-04-fx-multicurrency.md's pair list for one reason: it has <b>zero</b> minor
 * digits, so it "will catch every place the code assumes two". One yen is the smallest thing that
 * can exist in a JPY account - there is no such unit as a hundredth of a yen, and any code that
 * quietly produces one has invented money.
 *
 * <p>The trap is that a {@code long} of minor units looks identical whichever currency it belongs
 * to: {@code 16042} is 160.42 EUR but 16042 JPY, and code that hard-codes a factor of 100 is wrong
 * by two orders of magnitude without ever failing a type check. These tests pin the conversion at
 * both ends - into JPY and back out of it - and assert that the sub-yen part of a conversion lives
 * in the residual, which is a fraction by design, rather than in the amount, which must not be.
 */
class JpyZeroDigitTest {

    @Nested
    @DisplayName("EUR to JPY produces whole yen")
    class EurToJpy {

        @Test
        void anExactRateProducesWholeYenAndNoResidual() {
            // 100.00 EUR * 160.42 = 16042 JPY exactly - 16042 yen, not 16042 hundredths of one.
            FxConversion result = FxMath.convertWithResidual(Money.of(10_000, "EUR"), "160.42", "JPY");

            assertThat(result.converted()).isEqualTo(Money.of(16_042, "JPY"));
            assertThat(result.isExact()).isTrue();
            assertThat(result.converted()).hasToString("16042 JPY");
        }

        @Test
        void theRoundingBoundaryIsTheWholeYenNotTheHundredthOfAYen() {
            // 100.00 EUR * 160.425 = 16042.5 JPY: a tie at the *yen*, resolved HALF_EVEN to 16042
            // because 2 is even. Code assuming two minor digits would see 1604250 hundredths here
            // and round nothing at all.
            FxConversion result = FxMath.convertWithResidual(Money.of(10_000, "EUR"), "160.425", "JPY");

            assertThat(result.converted()).isEqualTo(Money.of(16_042, "JPY"));
            assertThat(result.residualMinorFraction()).isEqualTo("0.5");
            assertThat(result.exactMinorUnits()).isEqualTo("16042.5");
        }

        @Test
        void theTieAtTheWholeYenAlsoRoundsUpToAnEvenNeighbour() {
            // 100.00 EUR * 160.435 = 16043.5 JPY -> 16044, the even neighbour.
            FxConversion result = FxMath.convertWithResidual(Money.of(10_000, "EUR"), "160.435", "JPY");

            assertThat(result.converted()).isEqualTo(Money.of(16_044, "JPY"));
            assertThat(result.residualMinorFraction()).isEqualTo("-0.5");
        }

        @Test
        void aSubYenTailIsCarriedByTheResidualAndNeverByTheAmount() {
            // 100.01 EUR * 160.425 = 16044.10425 JPY. The 0.10425 is a fraction of a yen: it has
            // to sit in the residual, because Money cannot express it and the ledger cannot post it.
            FxConversion result = FxMath.convertWithResidual(Money.of(10_001, "EUR"), "160.425", "JPY");

            assertThat(result.converted()).isEqualTo(Money.of(16_044, "JPY"));
            assertThat(result.residualMinorFraction()).isEqualTo("0.10425");
            assertThat(result.exactMinorUnits()).isEqualTo("16044.10425");
        }

        @Test
        void anAmountWorthLessThanOneYenConvertsToZeroYenWithTheValueInTheResidual() {
            // 0.01 EUR at a deliberately tiny rate of 0.4 is 0.004 yen - and 0.004 yen is 0.004 of
            // JPY's *minor* unit, because for JPY the minor unit and the major unit are the same
            // thing. In EUR the same product would be 0.4 of a cent; the factor of 100 between
            // those two readings is precisely the assumption this test exists to catch. Nothing is
            // invented and nothing is lost: the amount is zero yen and the residual holds all of it.
            FxConversion result = FxMath.convertWithResidual(Money.of(1, "EUR"), "0.4", "JPY");

            assertThat(result.converted()).isEqualTo(Money.zero("JPY"));
            assertThat(result.residualMinorFraction()).isEqualTo("0.004");
            assertThat(result.exactMinorUnits()).isEqualTo("0.004");

            // The EUR reading of the identical product, for contrast: 0.4 of a cent.
            assertThat(FxMath.convertWithResidual(Money.of(1, "EUR"), "0.4", "EUR")
                    .residualMinorFraction()).isEqualTo("0.4");
        }
    }

    @Nested
    @DisplayName("JPY back out to a two-digit currency")
    class JpyToEur {

        @Test
        void aWholeYenAmountConvertsOutExactlyWhenTheRateAllows() {
            // 1000 JPY * 0.00625 = 6.25 EUR. The source scale is zero, so nothing on the way in
            // needs rounding - only the two-digit target does.
            FxConversion result = FxMath.convertWithResidual(Money.of(1_000, "JPY"), "0.00625", "EUR");

            assertThat(result.converted()).isEqualTo(Money.of(625, "EUR"));
            assertThat(result.isExact()).isTrue();
        }

        @Test
        void theResidualComingOutOfJpyIsAFractionOfACentNotOfAYen() {
            // 1000 JPY * 0.006256 = 6.256 EUR = 625.6 cents -> 626 cents, residual -0.4 of a cent.
            // The residual is always denominated in the *target* currency's minor unit.
            FxConversion result = FxMath.convertWithResidual(Money.of(1_000, "JPY"), "0.006256", "EUR");

            assertThat(result.converted()).isEqualTo(Money.of(626, "EUR"));
            assertThat(result.residualMinorFraction()).isEqualTo("-0.4");
            assertThat(result.exactMinorUnits()).isEqualTo("625.6");
        }

        @Test
        void aRoundTripThroughJpyLandsBackOnWholeCentsWithTheInversionsRoundingInTheResidual() {
            // 100.00 EUR -> 16042 JPY at 160.42, then back at the derived inverse rate. The inverse
            // is only exact to the stored scale of 10, so the return leg is a hair under 100.00 EUR
            // - and that hair is in the residual, where it can be seen, rather than absorbed.
            Money yen = FxMath.convertWithResidual(Money.of(10_000, "EUR"), "160.42", "JPY").converted();
            FxConversion back = FxMath.convertWithResidual(yen, FxMath.invert("160.42"), "EUR");

            assertThat(yen).isEqualTo(Money.of(16_042, "JPY"));
            assertThat(back.converted()).isEqualTo(Money.of(10_000, "EUR"));
            assertThat(back.isExact()).isFalse();
            assertThat(back.residualMinorFraction()).isEqualTo("-0.00000586");
        }
    }

    @Nested
    @DisplayName("no hundredth of a yen anywhere")
    class NoHundredthOfAYen {

        @Test
        void yenAmountsRenderWithNoDecimalPointAtAll() {
            assertThat(Money.of(1, "JPY")).hasToString("1 JPY");
            assertThat(Money.zero("JPY")).hasToString("0 JPY");
            assertThat(FxMath.convertWithResidual(Money.of(10_000, "EUR"), "160.42", "JPY")
                    .converted().toString()).doesNotContain(".");
        }

        @Test
        void oneMinorUnitOfJpyIsOneYenNotOneHundredthOfOne() {
            // The same long means different things per currency; this is the assumption that breaks
            // code written against EUR only.
            assertThat(Money.of(1, "JPY")).hasToString("1 JPY");
            assertThat(Money.of(1, "EUR")).hasToString("0.01 EUR");
        }

        @Test
        void aWholeYenConversionReconstructsAsAWholeNumberToo() {
            // exactMinorUnits() is yen here, so an exact conversion has no decimal part either.
            assertThat(FxMath.convertWithResidual(Money.of(10_000, "EUR"), "160.42", "JPY")
                    .exactMinorUnits()).isEqualTo("16042");
        }

        @Test
        void arithmeticOnYenStaysInWholeYen() {
            // Nothing downstream of a conversion can reintroduce a sub-yen amount either: Money's
            // arithmetic is integral, so a half-yen has nowhere to appear.
            Money yen = FxMath.convertWithResidual(Money.of(10_000, "EUR"), "160.425", "JPY").converted();

            assertThat(yen.plus(Money.of(1, "JPY"))).hasToString("16043 JPY");
            assertThat(yen.minus(Money.of(42, "JPY"))).hasToString("16000 JPY");
        }
    }
}
