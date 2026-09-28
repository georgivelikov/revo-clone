package com.revolutclone.card.testsupport;

import com.revolutclone.card.ledger.HeldFunds;
import com.revolutclone.card.ledger.HoldFundsCommand;
import com.revolutclone.card.ledger.LedgerInsufficientFundsException;
import com.revolutclone.card.ledger.LedgerRejectedSettlementException;
import com.revolutclone.card.ledger.LedgerReservations;
import com.revolutclone.card.ledger.SettleFundsCommand;
import com.revolutclone.card.ledger.SettledFunds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A ledger that really does hold, capture and release funds, in memory, with the one distinction this
 * whole phase turns on: <strong>a hold moves money out of {@code available} and leaves {@code total}
 * exactly where it was; a capture is what finally moves {@code total}.</strong> A hold is a promise; a
 * capture is accounting.
 *
 * <p>A fake rather than the real service because {@code testcontainers-kafka} cannot start on this
 * machine (docs/spring-boot-4-notes.md), so no test here can run ledger-service alongside card-service.
 * The rules themselves are already proven where they live: ledger-service's
 * {@code ReservedFundsAreNotAvailableTest}, {@code PartialCaptureTest},
 * {@code CaptureDoesNotDoubleDecrementTest} and - for the shape of the postings and the zero-sum
 * invariant under card traffic - {@code ZeroSumAfterCardActivityTest}. What this task's tests need from
 * a ledger is that card-service <em>asks</em>, asks for the right amount against the right hold under
 * the right idempotency key, and draws the right conclusion from the answer.
 *
 * <p><strong>Balances are modelled, not merely recorded</strong>, because three of the four settlement
 * cases are statements about balances that nothing else in card-service can check: an exact settlement
 * makes available and total agree again, a partial returns the difference, and an over-settlement can
 * take both below zero. A fake that only counted calls would let all three pass while the arithmetic
 * was wrong.
 *
 * <p><strong>Every method is {@code synchronized}, because the real ledger is a service and this one is
 * a {@link HashMap}.</strong> {@code ConcurrentAuthLimitTest} drives the decision chain from several
 * threads at once, which is the only honest way to test the hazard the phase names - and an
 * unsynchronised map under that load loses updates of its own. The failure would look exactly like a
 * limit bug: two taps approved where one should have been, but caused by the fixture rather than by the
 * code under test. A test that cannot distinguish those two is worse than no test, so the fake is made
 * to behave like the single-threaded authority it stands in for.
 */
public class FakeLedgerReservations implements LedgerReservations {

    private final Map<UUID, Long> totalMinorByAccount = new HashMap<>();
    private final Map<UUID, Long> availableMinorByAccount = new HashMap<>();
    private final Map<String, HeldFunds> byIdempotencyKey = new HashMap<>();
    private final Map<UUID, Hold> holdsById = new HashMap<>();
    private final Map<String, SettledFunds> settlementsByNetworkRef = new HashMap<>();
    private final List<HoldFundsCommand> holds = new ArrayList<>();
    private final List<SettleFundsCommand> settlements = new ArrayList<>();
    private final List<UUID> releases = new ArrayList<>();
    private final List<UUID> expiries = new ArrayList<>();

    /** Funds an account: {@code total} and {@code available} start out equal, as a real balance does. */
    public synchronized void fund(UUID ledgerAccountId, long amountMinor) {
        totalMinorByAccount.put(ledgerAccountId, amountMinor);
        availableMinorByAccount.put(ledgerAccountId, amountMinor);
    }

    public synchronized long availableMinor(UUID ledgerAccountId) {
        return availableMinorByAccount.getOrDefault(ledgerAccountId, 0L);
    }

    public synchronized long totalMinor(UUID ledgerAccountId) {
        return totalMinorByAccount.getOrDefault(ledgerAccountId, 0L);
    }

    public synchronized List<HoldFundsCommand> holds() {
        return List.copyOf(holds);
    }

    /** Every settlement asked for, in order - so a test can assert what card-service converted to. */
    public synchronized List<SettleFundsCommand> settlements() {
        return List.copyOf(settlements);
    }

    /** Every hold released without a posting - the reversal path, and nothing else should appear here. */
    public synchronized List<UUID> releases() {
        return List.copyOf(releases);
    }

    /** Every hold the seven-day sweep asked the ledger to expire, in order. */
    public synchronized List<UUID> expiries() {
        return List.copyOf(expiries);
    }

    /** How much of a hold is still held. Zero once it has been captured or released. */
    public synchronized long heldMinor(UUID reservationId) {
        Hold hold = holdsById.get(reservationId);
        return hold == null || hold.settled ? 0L : hold.amountMinor;
    }

    public synchronized void reset() {
        totalMinorByAccount.clear();
        availableMinorByAccount.clear();
        byIdempotencyKey.clear();
        holdsById.clear();
        settlementsByNetworkRef.clear();
        holds.clear();
        settlements.clear();
        releases.clear();
        expiries.clear();
    }

    @Override
    public synchronized HeldFunds hold(HoldFundsCommand command) {
        // Idempotent on the caller's key, exactly as ledger-service's own ReservationService is: a
        // resent authorization must land on the hold the first attempt created.
        HeldFunds alreadyHeld = byIdempotencyKey.get(command.idempotencyKey());
        if (alreadyHeld != null) {
            return alreadyHeld;
        }

        long available = availableMinor(command.ledgerAccountId());
        if (available < command.amountMinor()) {
            throw new LedgerInsufficientFundsException("could not hold " + command.amountMinor()
                    + " minor units of " + command.currency() + " on ledger account "
                    + command.ledgerAccountId() + ": available " + available);
        }

        // available falls, total does not. The difference is the pending authorization.
        availableMinorByAccount.put(command.ledgerAccountId(), available - command.amountMinor());

        HeldFunds held = new HeldFunds(UUID.randomUUID(), command.ledgerAccountId(), command.amountMinor(),
                command.currency(), command.expiresAt());
        byIdempotencyKey.put(command.idempotencyKey(), held);
        holdsById.put(held.id(), new Hold(command.ledgerAccountId(), command.amountMinor()));
        holds.add(command);
        return held;
    }

    /**
     * Captures against the hold and posts anything beyond it, exactly as {@code CardSettlementService}
     * does - including the split, which is the ledger's to make because only the ledger knows the hold.
     *
     * <p>The balance arithmetic is the whole point of this method:
     * <ul>
     *   <li><strong>the captured part</strong> leaves {@code total} and does <em>not</em> touch
     *       {@code available} a second time, because the hold already moved it out at hold time. Doing
     *       both is the double-decrement bug {@code PostingEngine}'s balance-column table exists to
     *       prevent, and it is invisible to the zero-sum check;</li>
     *   <li><strong>the released remainder</strong> goes back to {@code available} only - it was never
     *       taken out of {@code total};</li>
     *   <li><strong>the excess</strong> is an ordinary debit: both columns fall, with no floor, which is
     *       how a wallet legitimately goes negative.</li>
     * </ul>
     */
    @Override
    public synchronized SettledFunds settle(UUID reservationId, SettleFundsCommand command) {
        SettledFunds alreadySettled = settlementsByNetworkRef.get(command.networkRef());
        if (alreadySettled != null) {
            return alreadySettled;
        }

        Hold hold = holdsById.get(reservationId);
        if (hold == null) {
            throw new LedgerRejectedSettlementException("no such hold: " + reservationId);
        }
        if (hold.settled) {
            throw new LedgerRejectedSettlementException("hold " + reservationId + " is no longer held");
        }
        if (command.settledMinor() <= 0) {
            throw new LedgerRejectedSettlementException("settledMinor must be > 0, got " + command.settledMinor());
        }

        long capturedMinor = Math.min(command.settledMinor(), hold.amountMinor);
        long releasedRemainderMinor = hold.amountMinor - capturedMinor;
        long excessMinor = command.settledMinor() - capturedMinor;
        if (command.feeMinor() < 0 || command.feeMinor() > capturedMinor) {
            throw new LedgerRejectedSettlementException("feeMinor " + command.feeMinor()
                    + " is not within the captured amount " + capturedMinor);
        }

        totalMinorByAccount.merge(hold.ledgerAccountId, -capturedMinor, Long::sum);
        if (releasedRemainderMinor > 0) {
            availableMinorByAccount.merge(hold.ledgerAccountId, releasedRemainderMinor, Long::sum);
        }
        if (excessMinor > 0) {
            totalMinorByAccount.merge(hold.ledgerAccountId, -excessMinor, Long::sum);
            availableMinorByAccount.merge(hold.ledgerAccountId, -excessMinor, Long::sum);
        }
        hold.settled = true;
        hold.captured = true;

        SettledFunds settled = new SettledFunds(UUID.randomUUID(), capturedMinor, releasedRemainderMinor,
                excessMinor, excessMinor > 0 ? UUID.randomUUID() : null);
        settlementsByNetworkRef.put(command.networkRef(), settled);
        settlements.add(command);
        return settled;
    }

    /** {@code available} goes back up, {@code total} never moved, and no entry id is ever produced. */
    @Override
    public synchronized void release(UUID reservationId) {
        Hold hold = holdsById.get(reservationId);
        if (hold == null) {
            throw new LedgerRejectedSettlementException("no such hold: " + reservationId);
        }
        releases.add(reservationId);
        if (hold.settled) {
            // Idempotent for an already-released hold, as the real ledger is; a captured one would
            // throw there, and no test here reaches that because card-service checks the row first.
            return;
        }
        availableMinorByAccount.merge(hold.ledgerAccountId, hold.amountMinor, Long::sum);
        hold.settled = true;
    }

    /**
     * The seven-day sweep's return of funds: the same arithmetic {@link #release} performs, and
     * idempotent on a hold that has already been given back - the real ledger's {@code /expire} is
     * idempotent on both {@code EXPIRED} and {@code RELEASED} precisely because its own sweep races
     * card-service's for these rows. A captured hold is refused here as it is there: that money moved.
     */
    @Override
    public synchronized void expire(UUID reservationId) {
        Hold hold = holdsById.get(reservationId);
        if (hold == null) {
            throw new LedgerRejectedSettlementException("no such hold: " + reservationId);
        }
        if (hold.captured) {
            throw new LedgerRejectedSettlementException("hold " + reservationId + " has been captured; "
                    + "money that has moved cannot be given back by a sweep");
        }
        expiries.add(reservationId);
        if (hold.settled) {
            return;
        }
        availableMinorByAccount.merge(hold.ledgerAccountId, hold.amountMinor, Long::sum);
        hold.settled = true;
    }

    /** A hold, and whether it has reached a terminal state. Mutable because the real row is too. */
    private static final class Hold {

        private final UUID ledgerAccountId;
        private final long amountMinor;

        /** No longer held, by any route: captured, released or expired. */
        private boolean settled;

        /**
         * Captured specifically - the one terminal state that is not "the money came back", and the
         * one the real ledger refuses to expire. {@code settled} alone cannot tell the difference.
         */
        private boolean captured;

        private Hold(UUID ledgerAccountId, long amountMinor) {
            this.ledgerAccountId = ledgerAccountId;
            this.amountMinor = amountMinor;
        }
    }
}
