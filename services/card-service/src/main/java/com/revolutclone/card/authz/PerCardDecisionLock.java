package com.revolutclone.card.authz;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Makes "check the limit, then hold, then record" indivisible per card.
 *
 * <p><strong>Why anything is needed at all.</strong> plans/phase-05-cards.md is explicit about the
 * hazard: "Keyed by {@code cardId}, so two taps on the same card are evaluated in order against the same
 * limits. This is the clearest possible justification for the partition-key rule from Phase 00 - with
 * random partitioning, two simultaneous auths could both pass a limit check that only one should." The
 * window is computed from committed {@code card_authorization} rows, so a second evaluation that starts
 * before the first one's row is committed reads a stale total and approves a tap that takes the card over
 * its ceiling. Nothing about the limit is wrong; the interleaving is.
 *
 * <p><strong>The durable guarantee is the partition key, and this is not a replacement for it.</strong>
 * {@code card.authorization.v1} is keyed by {@code cardId} (CLAUDE.md rule 5), so one partition carries
 * every tap on a card and one consumer thread owns that partition - taps on a card arrive in order and
 * are decided one at a time, across the whole cluster, which no in-process lock could achieve. What this
 * adds is the case the partition key does not cover: more than one thread inside <em>this</em> process
 * deciding on the same card. Raising listener concurrency, an admin endpoint that authorizes directly,
 * or a test driving the handler from two threads all produce exactly the interleaving above, and in each
 * of them the broker's ordering is simply not in the picture. {@code ConcurrentAuthLimitTest} is the
 * third of those.
 *
 * <p><strong>Why not a database lock.</strong> A {@code select ... for update} on the card row, or a
 * transactional advisory lock, would serialize across processes too - but only by holding a database
 * transaction across step 6's HTTP call to ledger-service, which is precisely what
 * {@link AuthorizationRecorder} explains this service must not do: a transaction cannot roll a hold back,
 * and a slow ledger would then pin a connection as well as a lock. A lock that is coarser than the
 * broker's own ordering and cheaper than a held transaction is the right size for the gap being closed.
 *
 * <p><strong>Striped, not one lock per card.</strong> A fixed array indexed by the card id's hash cannot
 * grow without bound, which a map keyed by card id would - entries in it would have to be either leaked
 * or reference-counted, and a reference-counting bug here is a lock that stops locking. Two different
 * cards can collide on a stripe and wait for each other; that costs one authorization a few milliseconds
 * and costs correctness nothing, which is the right way round.
 */
@Component
public class PerCardDecisionLock {

    /**
     * Comfortably more stripes than a card-service instance has consumer threads, so a collision is
     * rare rather than routine. A power of two only for tidiness - {@link Math#floorMod} does not care.
     */
    private static final int STRIPES = 64;

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];

    public PerCardDecisionLock() {
        for (int i = 0; i < STRIPES; i++) {
            // Fair, so a card under sustained load cannot starve one unlucky tap indefinitely. The
            // throughput fair locks cost is irrelevant at the contention this ever sees - by the
            // partition-key argument above, the expected number of waiters is zero.
            stripes[i] = new ReentrantLock(true);
        }
    }

    /**
     * Runs {@code decision} with this card's stripe held.
     *
     * <p>Reentrant, so a decision path that ends up back in here on the same card deadlocks against
     * nothing. The lock is released however {@code decision} ends, including by exception - a card whose
     * authorization threw must still be authorizable on the retry.
     */
    public <T> T decide(UUID cardId, Supplier<T> decision) {
        ReentrantLock stripe = stripes[Math.floorMod(cardId.hashCode(), STRIPES)];
        stripe.lock();
        try {
            return decision.get();
        } finally {
            stripe.unlock();
        }
    }
}
