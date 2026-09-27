package com.revolutclone.fx.quote;

import com.revolutclone.contracts.events.FxExecutionFailedV1;
import com.revolutclone.contracts.events.FxExecutionRequestedV1;
import com.revolutclone.fx.ledger.LedgerReservations;
import com.revolutclone.fx.ledger.LedgerUnavailableException;
import com.revolutclone.fx.quote.error.BuyWalletNotFoundException;
import com.revolutclone.fx.quote.error.QuoteNotExecutableException;
import com.revolutclone.fx.quote.error.QuoteNotFoundException;
import com.revolutclone.fx.readmodel.WalletResolver;
import com.revolutclone.messaging.api.OutboxAppender;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * plans/phase-04-fx-multicurrency.md's <strong>Executing</strong>: {@code POST
 * /api/fx/quotes/{id}/execute}. "Quote must be {@code ACTIVE} and unexpired - check server-side,
 * never trust the client's clock. Emits {@code FxExecutionRequestedV1} via the outbox; the ledger
 * captures the reservation and posts the entry."
 *
 * <p><strong>One transaction, one row, one event.</strong> Marking the quote {@code CONSUMED} and
 * appending the outbox row happen together or not at all (CLAUDE.md rule 6), and the transition is
 * a conditional update rather than a read-then-write - see
 * {@link FxQuoteRepository#markConsumed}. That is what makes two concurrent executes of one quote
 * produce one event and therefore one journal entry: the loser's update matches no row, so it
 * appends nothing and returns the winner's quote. The ledger's {@code fxquote:<id>} idempotency
 * key is a second line of defence for a redelivered event, not the primary guard.
 *
 * <p><strong>Executing calls nothing synchronously.</strong> Unlike quoting - which must reserve
 * before it can answer - execution has nothing to tell the user that cannot wait: the funds are
 * already held, so the exchange will happen. The ledger learns about it from the topic, which is
 * also why killing fx-service immediately after an execute still settles.
 *
 * <p><strong>Compensation</strong> is {@link #compensate}: the ledger's {@code EntryRejectedV1} for
 * an {@code fxquote:} key comes back on {@code ledger.entry.v1}, the quote goes {@code CANCELLED},
 * the hold is released and {@link FxExecutionFailedV1} tells the user. This is the phase's first
 * genuine compensation path, and it exists because the quote's state lives here while its
 * reservation lives in the ledger.
 */
@Service
public class QuoteExecutionService {

    private static final Logger log = LoggerFactory.getLogger(QuoteExecutionService.class);

    /** Keyed by quoteId, 30-day retention - plans/phase-00-foundations.md's topic catalogue. */
    private static final String OUTBOX_TOPIC = "fx.quote.v1";

    private final FxQuoteRepository quotes;
    private final WalletResolver wallets;
    private final LedgerReservations ledger;
    private final OutboxAppender outboxAppender;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public QuoteExecutionService(FxQuoteRepository quotes, WalletResolver wallets, LedgerReservations ledger,
            OutboxAppender outboxAppender, TransactionTemplate transactions, Clock clock) {
        this.quotes = quotes;
        this.wallets = wallets;
        this.ledger = ledger;
        this.outboxAppender = outboxAppender;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @param quoteId the quote to execute
     * @param userId  the authenticated caller - a quote is only executable by the user it was
     *                issued to, and by nobody else even with its id
     * @return the quote in its post-execution state
     * @throws QuoteNotFoundException      if no such quote belongs to this caller
     * @throws QuoteNotExecutableException if it has expired or already ended
     * @throws BuyWalletNotFoundException  if the caller has no wallet to be paid into
     */
    @Transactional
    public FxQuote execute(UUID quoteId, UUID userId) {
        FxQuote quote = quotes.findById(quoteId)
                .filter(found -> found.userId().equals(userId))
                .orElseThrow(() -> new QuoteNotFoundException(quoteId));

        // Executing an already-executed quote is not an error: a quote is a one-shot resource, so
        // the second call's outcome is the first call's outcome. This is how the endpoint honours a
        // retried Idempotency-Key without a second key space - the quote id is the unit of work.
        if (quote.state() == FxQuoteState.CONSUMED) {
            return quote;
        }
        if (quote.state() != FxQuoteState.ACTIVE) {
            throw QuoteNotExecutableException.inState(quoteId, quote.state());
        }
        Instant now = clock.instant();
        if (quote.isExpiredAt(now)) {
            // Refused on the server clock, before anything moves. The sweep (task 04.6) is what
            // releases the hold; this only declines to spend it.
            throw QuoteNotExecutableException.expired(quoteId);
        }
        if (quote.reservationId() == null) {
            // Unreachable from the quoting path, which reserves before it persists - but an
            // execution with no hold behind it would ask the ledger to capture nothing, so it is
            // refused here rather than discovered there.
            throw QuoteNotExecutableException.inState(quoteId, quote.state());
        }

        UUID buyLedgerAccountId = wallets.resolveWalletLedgerAccount(userId, quote.buyCurrency())
                .orElseThrow(() -> new BuyWalletNotFoundException(quote.buyCurrency()));

        if (quotes.markConsumed(quoteId, now) == 0) {
            // Lost the race, or the row expired between the check above and this statement. Either
            // way somebody else's transaction decided the outcome; report theirs rather than
            // executing a second time.
            return quotes.findById(quoteId)
                    .filter(current -> current.state() == FxQuoteState.CONSUMED)
                    .orElseThrow(() -> QuoteNotExecutableException.expired(quoteId));
        }

        FxLegs legs = quote.legs();
        outboxAppender.append(OUTBOX_TOPIC, quoteId.toString(), new FxExecutionRequestedV1(
                quoteId, userId, quote.reservationId(), buyLedgerAccountId,
                quote.sellCurrency(), quote.sellMinor(), quote.buyCurrency(),
                legs.poolMidMinor(), legs.clientBuyMinor(), legs.feeMinor(), legs.pnlMinor()));

        log.info("quote {} executed: selling {} {} for {} {} (fee {}, residual {})", quoteId,
                quote.sellMinor(), quote.sellCurrency(), legs.clientBuyMinor(), quote.buyCurrency(),
                legs.feeMinor(), legs.pnlMinor());
        return new FxQuote(quote.id(), quote.idempotencyKey(), quote.userId(), quote.sellCurrency(),
                quote.buyCurrency(), quote.sellMinor(), quote.buyMinor(), quote.midRate(), quote.clientRate(),
                quote.spreadBps(), quote.reservationId(), FxQuoteState.CONSUMED, quote.expiresAt(),
                quote.createdAt());
    }

    /**
     * The compensation the phase's table describes: "Ledger rejects the capture (funds moved after
     * the hold) - fx-service marks the quote {@code CANCELLED}, releases the reservation, emits
     * {@code FxExecutionFailedV1}."
     *
     * <p>Order matters, and so does which parts share a transaction. {@code CANCELLED} and the
     * {@link FxExecutionFailedV1} that announces it are one transaction - the cross-service write
     * has to be atomic with the change it describes, which is the whole point of the outbox - and
     * the transition is conditional on {@code CONSUMED}, so a redelivered rejection compensates
     * once. Releasing the hold is deliberately <em>outside</em> that transaction: it is an HTTP
     * call to another service and cannot be rolled back, so holding a row lock across it would buy
     * nothing but contention. It is also best effort - the ledger has usually released the hold
     * already (an expired reservation is the likeliest reason a capture was refused at all), and if
     * this service cannot reach the ledger, the hold's own expiry is the backstop. The user is told
     * either way, which is the part that cannot be left to a timeout.
     *
     * <p>The transaction is opened with a {@link TransactionTemplate} rather than by splitting this
     * into two {@code @Transactional} methods, because a call between two methods of one bean does
     * not pass through the proxy and the annotation on the callee would silently do nothing.
     *
     * @param reason the ledger's own rejection reason, passed through unchanged
     */
    public void compensate(UUID quoteId, String reason) {
        FxQuote cancelled = transactions.execute(status -> {
            if (quotes.markCancelled(quoteId) == 0) {
                return null;
            }
            FxQuote quote = quotes.findById(quoteId).orElseThrow(() -> new QuoteNotFoundException(quoteId));
            outboxAppender.append(OUTBOX_TOPIC, quoteId.toString(),
                    new FxExecutionFailedV1(quoteId, quote.userId(), reason));
            return quote;
        });

        if (cancelled == null) {
            log.info("ignoring a rejection for quote {}: it is not CONSUMED, so it was already "
                    + "compensated or never executed", quoteId);
            return;
        }
        log.warn("quote {} cancelled: the ledger rejected the capture ({})", quoteId, reason);

        if (cancelled.reservationId() != null) {
            try {
                ledger.release(cancelled.reservationId());
            } catch (LedgerUnavailableException ex) {
                // Not fatal and deliberately not rethrown: rethrowing would redeliver this event and
                // re-run a compensation whose visible half - CANCELLED, and the user being told -
                // has already happened. The funds come back on the hold's own expiry regardless.
                log.warn("could not release reservation {} while cancelling quote {}; the hold's own "
                        + "expiry will return the funds: {}", cancelled.reservationId(), quoteId,
                        ex.getMessage());
            }
        }
    }
}
