package com.revolutclone.fx.chaos;

import org.springframework.stereotype.Component;

/**
 * plans/phase-07-resilience-and-scale.md's Chaos control plane, {@code fx.down} lever:
 * "fx-service returns 503 - observe the circuit breaker." Always registered and inert by
 * default; only {@link ChaosFxAdminController}, which is {@code @Profile("chaos")}-gated, ever
 * flips it - see {@code com.revolutclone.messaging.outbox.ChaosOutboxControl} for why the state
 * holder itself needs no profile guard.
 */
@Component
public class ChaosFxState {

    private volatile boolean down;

    public void setDown(boolean down) {
        this.down = down;
    }

    public boolean isDown() {
        return down;
    }
}
