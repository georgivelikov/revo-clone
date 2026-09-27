package com.revolutclone.fx.quote;

import com.revolutclone.fx.ledger.LedgerReservations;
import com.revolutclone.fx.ledger.LedgerUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * plans/phase-04-fx-multicurrency.md's task 6: "An expiry sweep marks stale quotes {@code EXPIRED}
 * and releases their reservations."
 *
 * <p><strong>This is belt, not braces.</strong> A quote's {@code expires_at} and its reservation's
 * are the identical instant - {@link QuoteService} truncates the clock once and hands the same
 * value to both the ledger and the row it writes - so ledger-service's own reservation sweep
 * (task 02.10) already returns these funds to {@code available} on its own schedule with no help
 * from this service at all. That is the phase's whole point: "the reservation expiry means the
 * ledger does not depend on fx-service ever coming back." What this class buys on top of that
 * backstop is two things the ledger cannot do for us - marking {@code fx_quote.state} so the API
 * and a subsequent execute see {@code EXPIRED} rather than a stale {@code ACTIVE}, and releasing
 * the hold promptly rather than waiting for the ledger's own sweep interval, which is what makes
 * "let it expire without touching it" read as instant in the UI instead of laggy.
 *
 * <p><strong>The state transition and the release are deliberately not one atomic step.</strong>
 * {@link FxQuoteRepository#markExpired} is a single conditional {@code UPDATE}, already atomic on
 * its own and racing safely against {@link QuoteExecutionService#execute}'s own conditional
 * update of the same row - whichever transition's {@code where} clause matches first wins, and the
 * other affects zero rows. {@link LedgerReservations#release} is then called outside any
 * transaction, for the same reason {@code QuoteExecutionService.compensate} calls it outside one:
 * it is a synchronous call to another service that cannot be rolled back, and it is best effort -
 * the reservation's own expiry is the backstop if it fails, not a retry of this sweep.
 */
@Component
public class QuoteExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(QuoteExpirySweep.class);

    private final FxQuoteRepository quotes;
    private final LedgerReservations ledger;
    private final Clock clock;

    public QuoteExpirySweep(FxQuoteRepository quotes, LedgerReservations ledger, Clock clock) {
        this.quotes = quotes;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${revolut.fx.quote.sweep-delay-ms:5000}")
    public void sweepExpiredQuotes() {
        sweepDueAsOf(clock.instant());
    }

    /**
     * Exposed with an explicit {@code asOf} instant, like {@code ReservationService}'s own sweep,
     * so a test can drive one cycle deterministically instead of waiting on the scheduler or
     * manipulating the system clock.
     *
     * @return the number of quotes this call actually moved to {@code EXPIRED}
     */
    public int sweepDueAsOf(Instant asOf) {
        List<FxQuote> due = quotes.findActiveExpiredAsOf(asOf);
        int expired = 0;
        for (FxQuote quote : due) {
            if (quotes.markExpired(quote.id(), asOf) == 0) {
                // Lost the race: executed (or expired by a concurrent sweep tick) between the
                // select above and this update reaching it. Its own outcome already decided
                // whatever happens to its reservation, so there is nothing left to do here.
                continue;
            }
            expired++;
            if (quote.reservationId() == null) {
                continue;
            }
            try {
                ledger.release(quote.reservationId());
            } catch (LedgerUnavailableException ex) {
                // Not fatal, and deliberately not retried by this sweep: the quote is EXPIRED
                // regardless of whether this call succeeded, so it will not appear in
                // findActiveExpiredAsOf again. The reservation's own expiry - the same instant,
                // since quote and hold share one TTL - is what returns the funds if this call
                // could not.
                log.warn("could not release reservation {} for expired quote {}; the hold's own "
                        + "expiry will return the funds: {}", quote.reservationId(), quote.id(),
                        ex.getMessage());
            }
        }
        return expired;
    }
}
