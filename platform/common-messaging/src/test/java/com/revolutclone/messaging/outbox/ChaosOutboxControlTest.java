package com.revolutclone.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChaosOutboxControlTest {

    @Test
    void startsResumed() {
        assertThat(new ChaosOutboxControl().isPaused()).isFalse();
    }

    @Test
    void pauseThenResumeRoundTrips() {
        ChaosOutboxControl control = new ChaosOutboxControl();

        control.pause();
        assertThat(control.isPaused()).isTrue();

        control.resume();
        assertThat(control.isPaused()).isFalse();
    }
}
