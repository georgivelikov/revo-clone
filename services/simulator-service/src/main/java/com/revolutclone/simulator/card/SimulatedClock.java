package com.revolutclone.simulator.card;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * plans/phase-05-cards.md's "clock you control": "an endpoint that advances a simulated day,
 * rather than waiting 24 hours." Governs only when this simulator submits a settlement batch for
 * its own recorded authorizations ({@link SpendRegistry}) - it has nothing to do with, and does not
 * move, card-service's real seven-day hold expiry (task 05.7), which runs on wall-clock time so the
 * funds come back even if nobody ever calls {@code /simulator/clock/advance-day}.
 *
 * <p>Day 0 at startup, incrementing by exactly one per call - no wall-clock reads anywhere in this
 * class, which is what makes settlement reachable in a demo or a test without waiting.
 */
@Component
public class SimulatedClock {

    private final AtomicInteger currentDay = new AtomicInteger(0);

    public int currentDay() {
        return currentDay.get();
    }

    /** @return the new current day, one past what it was */
    public int advance() {
        return currentDay.incrementAndGet();
    }
}
