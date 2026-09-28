package com.revolutclone.web.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PanMaskingTest {

    @Test
    void masksASixteenDigitRunKeepingOnlyTheLastFour() {
        assertThat(PanMasking.mask("pan=9999994579309822"))
                .isEqualTo("pan=************9822");
    }

    @Test
    void masksEveryPanShapedRunInAString() {
        assertThat(PanMasking.mask("from 4000000000001091 to 4000000000002082"))
                .isEqualTo("from ************1091 to ************2082");
    }

    @Test
    void leavesShortDigitRunsAlone() {
        // A CVV, an amountMinor, a 4-digit PIN - none of these are 12 digits or longer.
        assertThat(PanMasking.mask("cvv=048 amountMinor=4250 pin=1234")).isEqualTo("cvv=048 amountMinor=4250 pin=1234");
    }

    @Test
    void leavesTextWithNoDigitsAlone() {
        assertThat(PanMasking.mask("authorization approved for Tesco")).isEqualTo("authorization approved for Tesco");
    }

    @Test
    void aDigitRunLongerThanNineteenIsNotPanShapedAndIsLeftAlone() {
        String twentyDigits = "12345678901234567890";
        assertThat(PanMasking.mask(twentyDigits)).isEqualTo(twentyDigits);
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertThat(PanMasking.mask(null)).isNull();
        assertThat(PanMasking.mask("")).isEmpty();
    }
}
