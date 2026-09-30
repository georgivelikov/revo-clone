package com.revolutclone.ledger.chaos;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * plans/phase-07-resilience-and-scale.md's Chaos control plane: the {@code ledger.delay},
 * {@code ledger.fail-rate} and {@code ledger.reject-all} levers. Always registered - the fields
 * default to inert (no delay, no injected failures, nothing force-rejected) - and mutated only by
 * {@link ChaosLedgerAdminController}, which is {@code @Profile("chaos")}-gated. See
 * {@code com.revolutclone.messaging.outbox.ChaosOutboxControl} for why the state holder itself
 * does not need the profile guard.
 *
 * <p>Fields are {@code volatile}, not behind a lock: the admin endpoint writes from an HTTP
 * thread, {@link com.revolutclone.ledger.posting.PostingEngine} reads from a Kafka consumer
 * thread, and a torn read of a single primitive is not a correctness concern here the way a torn
 * read of the balance columns would be.
 *
 * <p>The fail rate is an integer percentage (0-100), not a fraction: CLAUDE.md hard rule 1 bans
 * {@code double}/{@code float} in this codebase outright, and the agent/tripwire.ps1 gate enforces
 * that literally, with no carve-out for a non-money probability. {@code 30} means the same thing
 * {@code ledger.fail-rate=0.3} means in plans/phase-07-resilience-and-scale.md's lever table - only
 * the wire representation differs.
 */
@Component
public class ChaosLedgerState {

    private volatile long delayMs;
    private volatile int failRatePercent;
    private volatile boolean rejectAll;

    public void setDelayMs(long delayMs) {
        this.delayMs = delayMs;
    }

    public void setFailRatePercent(int failRatePercent) {
        this.failRatePercent = failRatePercent;
    }

    public void setRejectAll(boolean rejectAll) {
        this.rejectAll = rejectAll;
    }

    public void reset() {
        this.delayMs = 0;
        this.failRatePercent = 0;
        this.rejectAll = false;
    }

    public long delayMs() {
        return delayMs;
    }

    public int failRatePercent() {
        return failRatePercent;
    }

    public boolean rejectAll() {
        return rejectAll;
    }

    /** {@code ledger.delay=5000} - "Ledger consumer sleeps before handling". */
    public void applyDelay() {
        long delay = this.delayMs;
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code ledger.fail-rate=30} - "30% of handles throw transiently". */
    public boolean shouldFailTransiently() {
        int percent = this.failRatePercent;
        return percent > 0 && ThreadLocalRandom.current().nextInt(100) < percent;
    }
}
