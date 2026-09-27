package com.revolutclone.fx.testsupport;

import com.revolutclone.fx.ledger.HeldFunds;
import com.revolutclone.fx.ledger.HoldFundsCommand;
import com.revolutclone.fx.ledger.LedgerReservations;
import com.revolutclone.fx.quote.error.InsufficientFundsForQuoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A ledger that really does hold funds, in memory: {@code available -= amount} on each hold, and a
 * refusal once it would go negative - the one rule of the ledger's own {@code hold} that the quoting
 * path's behaviour depends on.
 *
 * <p>A fake rather than the real service because {@code testcontainers-kafka} cannot start on this
 * machine (docs/spring-boot-4-notes.md), so no test here can run ledger-service alongside fx-service.
 * The rule itself is already proven where it lives: ledger-service's
 * {@code ReservedFundsAreNotAvailableTest} and {@code InsufficientFundsTest}. What
 * {@code QuoteReservesFundsTest} needs from this one is that fx-service <em>asks</em>, and that it
 * refuses to issue a quote when the answer is no.
 */
public class FakeLedgerReservations implements LedgerReservations {

    private final Map<UUID, Long> availableMinorByAccount = new HashMap<>();
    private final Map<String, HeldFunds> byIdempotencyKey = new HashMap<>();
    private final List<HoldFundsCommand> holds = new ArrayList<>();

    public void fund(UUID ledgerAccountId, long availableMinor) {
        availableMinorByAccount.put(ledgerAccountId, availableMinor);
    }

    public long availableMinor(UUID ledgerAccountId) {
        return availableMinorByAccount.getOrDefault(ledgerAccountId, 0L);
    }

    public List<HoldFundsCommand> holds() {
        return List.copyOf(holds);
    }

    public void reset() {
        availableMinorByAccount.clear();
        byIdempotencyKey.clear();
        holds.clear();
    }

    @Override
    public HeldFunds hold(HoldFundsCommand command) {
        HeldFunds alreadyHeld = byIdempotencyKey.get(command.idempotencyKey());
        if (alreadyHeld != null) {
            return alreadyHeld;
        }
        long available = availableMinor(command.ledgerAccountId());
        if (available < command.amountMinor()) {
            throw new InsufficientFundsForQuoteException(command.currency(), command.amountMinor(),
                    "available " + available);
        }
        availableMinorByAccount.put(command.ledgerAccountId(), available - command.amountMinor());
        HeldFunds held = new HeldFunds(UUID.randomUUID(), command.ledgerAccountId(), command.amountMinor(),
                command.currency(), command.expiresAt());
        byIdempotencyKey.put(command.idempotencyKey(), held);
        holds.add(command);
        return held;
    }
}
