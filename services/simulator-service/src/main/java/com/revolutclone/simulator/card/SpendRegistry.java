package com.revolutclone.simulator.card;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * This service's one piece of mutable state (besides {@link SimulatedClock}): every authorization
 * this simulator has created and not yet concluded, keyed by {@code networkRef}. In memory only -
 * see docs/architecture.md, this service owns no database - so a restart forgets every pending
 * authorization exactly as a real network's short-lived pre-auth file would age out on its own.
 *
 * <p>An entry is removed the instant it is concluded (settled or reversed), by
 * {@link #conclude(String)}. That single removal path is what makes both
 * {@link CardSpendService#reverse} and {@link CardSpendService#advanceDay} safe to call twice for
 * the same {@code networkRef}: the second call always finds nothing pending, whether the first
 * outcome was a settlement or a reversal, and reports it the same way an unrecognised ref would - a
 * concluded authorization.
 */
@Component
class SpendRegistry {

    private final Map<String, PendingSpend> pending = new ConcurrentHashMap<>();

    void record(String networkRef, PendingSpend spend) {
        pending.put(networkRef, spend);
    }

    /** Removes and returns the entry if it is still pending - the one way any entry ever leaves. */
    Optional<PendingSpend> conclude(String networkRef) {
        return Optional.ofNullable(pending.remove(networkRef));
    }

    /**
     * Every still-pending entry due to settle by {@code day}, removed as they are collected -
     * {@code ConcurrentHashMap}'s entry-set iterator supports {@code remove()} and is safe to mutate
     * through while iterating, unlike most {@code Map} implementations.
     */
    List<Map.Entry<String, PendingSpend>> dueBy(int day) {
        List<Map.Entry<String, PendingSpend>> due = new ArrayList<>();
        Iterator<Map.Entry<String, PendingSpend>> entries = pending.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, PendingSpend> entry = entries.next();
            if (entry.getValue().settleOnDay() <= day) {
                due.add(Map.entry(entry.getKey(), entry.getValue()));
                entries.remove();
            }
        }
        return due;
    }
}
