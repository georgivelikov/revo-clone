package com.revolutclone.card.testsupport;

import com.revolutclone.card.ledger.HeldFunds;
import com.revolutclone.card.ledger.HoldFundsCommand;
import com.revolutclone.card.ledger.LedgerInsufficientFundsException;
import com.revolutclone.card.ledger.LedgerReservations;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A ledger that really does hold funds, in memory, with the one distinction this whole phase turns on:
 * <strong>a hold moves money out of {@code available} and leaves {@code total} exactly where it
 * was.</strong> No posting, no accounting - a promise.
 *
 * <p>A fake rather than the real service because {@code testcontainers-kafka} cannot start on this
 * machine (docs/spring-boot-4-notes.md), so no test here can run ledger-service alongside card-service.
 * The rule itself is already proven where it lives: ledger-service's
 * {@code ReservedFundsAreNotAvailableTest} and {@code InsufficientFundsTest}. What this task's tests
 * need from a ledger is that card-service <em>asks</em> before approving, asks for the right amount on
 * the right account under the right idempotency key, and does not ask at all when the chain has already
 * stopped.
 */
public class FakeLedgerReservations implements LedgerReservations {

    private final Map<UUID, Long> totalMinorByAccount = new HashMap<>();
    private final Map<UUID, Long> availableMinorByAccount = new HashMap<>();
    private final Map<String, HeldFunds> byIdempotencyKey = new HashMap<>();
    private final List<HoldFundsCommand> holds = new ArrayList<>();

    /** Funds an account: {@code total} and {@code available} start out equal, as a real balance does. */
    public void fund(UUID ledgerAccountId, long amountMinor) {
        totalMinorByAccount.put(ledgerAccountId, amountMinor);
        availableMinorByAccount.put(ledgerAccountId, amountMinor);
    }

    public long availableMinor(UUID ledgerAccountId) {
        return availableMinorByAccount.getOrDefault(ledgerAccountId, 0L);
    }

    public long totalMinor(UUID ledgerAccountId) {
        return totalMinorByAccount.getOrDefault(ledgerAccountId, 0L);
    }

    public List<HoldFundsCommand> holds() {
        return List.copyOf(holds);
    }

    public void reset() {
        totalMinorByAccount.clear();
        availableMinorByAccount.clear();
        byIdempotencyKey.clear();
        holds.clear();
    }

    @Override
    public HeldFunds hold(HoldFundsCommand command) {
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
        holds.add(command);
        return held;
    }
}
