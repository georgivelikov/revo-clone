package com.revolutclone.ledger.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChaosLedgerStateTest {

    @Test
    void defaultsAreInert() {
        ChaosLedgerState state = new ChaosLedgerState();

        assertThat(state.delayMs()).isZero();
        assertThat(state.failRatePercent()).isZero();
        assertThat(state.rejectAll()).isFalse();
        assertThat(state.shouldFailTransiently()).isFalse();
    }

    @Test
    void failRateOfOneHundredPercentAlwaysFails() {
        ChaosLedgerState state = new ChaosLedgerState();
        state.setFailRatePercent(100);

        assertThat(state.shouldFailTransiently()).isTrue();
    }

    @Test
    void failRateOfZeroNeverFailsEvenAfterBeingSetNonZero() {
        ChaosLedgerState state = new ChaosLedgerState();
        state.setFailRatePercent(100);
        state.setFailRatePercent(0);

        assertThat(state.shouldFailTransiently()).isFalse();
    }

    @Test
    void resetClearsEveryLever() {
        ChaosLedgerState state = new ChaosLedgerState();
        state.setDelayMs(5_000);
        state.setFailRatePercent(30);
        state.setRejectAll(true);

        state.reset();

        assertThat(state.delayMs()).isZero();
        assertThat(state.failRatePercent()).isZero();
        assertThat(state.rejectAll()).isFalse();
    }

    @Test
    void applyDelayReturnsImmediatelyWhenNoDelayConfigured() {
        ChaosLedgerState state = new ChaosLedgerState();

        long start = System.nanoTime();
        state.applyDelay();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(100);
    }

    @Test
    void applyDelaySleepsForTheConfiguredDuration() {
        ChaosLedgerState state = new ChaosLedgerState();
        state.setDelayMs(50);

        long start = System.nanoTime();
        state.applyDelay();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(50);
    }
}
