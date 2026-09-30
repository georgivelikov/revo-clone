package com.revolutclone.fx.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChaosFxStateTest {

    @Test
    void defaultsToUp() {
        assertThat(new ChaosFxState().isDown()).isFalse();
    }

    @Test
    void togglesBothWays() {
        ChaosFxState state = new ChaosFxState();

        state.setDown(true);
        assertThat(state.isDown()).isTrue();

        state.setDown(false);
        assertThat(state.isDown()).isFalse();
    }
}
