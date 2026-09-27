package com.revolutclone.payment.testsupport;

import com.revolutclone.payment.fx.FxQuoteCommand;
import com.revolutclone.payment.fx.FxQuoteHold;
import com.revolutclone.payment.fx.FxQuoteUnavailableException;
import com.revolutclone.payment.fx.FxQuotes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A test double for {@link FxQuotes}, exactly like fx-service's own {@code FakeLedgerReservations}:
 * this service's real HTTP call goes to another service ({@code testcontainers-kafka} cannot start
 * on this machine, so nothing here can run fx-service alongside payment-service either - see
 * docs/spring-boot-4-notes.md).
 *
 * <p>Programmed with a single behaviour function rather than canned per-currency responses,
 * because every test that reaches this fake already knows exactly what one quote it is about to
 * ask for.
 */
public class FakeFxQuotes implements FxQuotes {

    private final List<FxQuoteCommand> calls = new ArrayList<>();
    private Function<FxQuoteCommand, FxQuoteHold> behavior = command -> {
        throw new IllegalStateException("FakeFxQuotes has no quote programmed for this test");
    };

    public void reset() {
        calls.clear();
        behavior = command -> {
            throw new IllegalStateException("FakeFxQuotes has no quote programmed for this test");
        };
    }

    /** Every subsequent call returns this same hold, whatever it asks for. */
    public void respondWith(FxQuoteHold hold) {
        behavior = command -> hold;
    }

    /** Every subsequent call fails the way fx-service being unreachable, or refusing, does. */
    public void unavailable(String reason) {
        behavior = command -> {
            throw new FxQuoteUnavailableException(reason);
        };
    }

    public List<FxQuoteCommand> calls() {
        return List.copyOf(calls);
    }

    @Override
    public FxQuoteHold quote(FxQuoteCommand command) {
        calls.add(command);
        return behavior.apply(command);
    }
}
