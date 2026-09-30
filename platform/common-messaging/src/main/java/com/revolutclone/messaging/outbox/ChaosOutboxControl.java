package com.revolutclone.messaging.outbox;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * plans/phase-07-resilience-and-scale.md's Chaos control plane, {@code outbox.pause} lever:
 * "Outbox poller stops - observe events stuck in the DB, then drain." Always registered, in
 * every service that carries {@code common-messaging} - not {@code @Profile("chaos")} gated
 * itself, because the flag defaults to {@code false} and is otherwise inert; only
 * {@code ChaosOutboxAdminController}, which IS profile-gated, can ever flip it. That split is
 * what lets {@link OutboxPublisher} take an unconditional constructor dependency instead of an
 * {@code Optional} that would need a null-check on every poll in every non-chaos build.
 */
@Component
public class ChaosOutboxControl {

    private final AtomicBoolean paused = new AtomicBoolean(false);

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public boolean isPaused() {
        return paused.get();
    }
}
