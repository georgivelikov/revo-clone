package com.revolutclone.ledger.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.EntryRejectedV1;
import com.revolutclone.contracts.events.FxExecutionRequestedV1;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.ledger.domain.Reservation;
import com.revolutclone.ledger.domain.ReservationRepository;
import com.revolutclone.ledger.domain.SystemAccountRefs;
import com.revolutclone.ledger.posting.EntryRejectionReason;
import com.revolutclone.ledger.posting.PostingLine;
import com.revolutclone.ledger.reservation.CaptureResult;
import com.revolutclone.ledger.reservation.ReservationService;
import com.revolutclone.ledger.reservation.error.CaptureExceedsHoldException;
import com.revolutclone.ledger.reservation.error.ReservationNotFoundException;
import com.revolutclone.ledger.reservation.error.ReservationNotHeldException;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.messaging.api.OutboxAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * plans/phase-04-fx-multicurrency.md's FX posting: the ledger half of executing a quote.
 * fx-service publishes {@link FxExecutionRequestedV1} on {@code fx.quote.v1} once a quote is
 * {@code CONSUMED}; this captures that quote's reservation and posts the exchange as <b>one</b>
 * journal entry of five legs, or six when a rounding residual has to be placed:
 *
 * <pre>
 *   DEBIT   alice:EUR         sellMinor        EUR   &lt;- captures the hold: `reserved` only
 *   CREDIT  system:fx-pool    sellMinor        EUR   &lt;- the pool takes on the exposure
 *   ------------------------------------------------- EUR nets to zero
 *   DEBIT   system:fx-pool    poolMidMinor     GBP   &lt;- the pool gives up the MID amount
 *   CREDIT  alice:GBP         clientBuyMinor   GBP   &lt;- the client is paid at the CLIENT rate
 *   CREDIT  system:fee-income feeMinor         GBP   &lt;- the spread, posted as revenue
 *   CREDIT  system:fx-pnl     pnlMinor         GBP   &lt;- only when the roundings did not cancel
 *   ------------------------------------------------- GBP nets to zero
 * </pre>
 *
 * <p><strong>Why one entry and not two.</strong> The ledger's invariant is per currency, so both
 * currencies balance independently through the pool, which is exactly what makes a single entry
 * possible: there is no intermediate state where the EUR has left and the GBP has not arrived, and
 * therefore nothing inside the ledger for the saga to compensate. The pool is left deliberately
 * unbalanced <em>in value</em> - long EUR, short GBP - because that is what an FX book is, and it
 * is why {@code FX_POOL} may go negative where a wallet may not.
 *
 * <p><strong>Why the pool leg is the mid amount and the fee leg exists at all.</strong> Four legs
 * at the client rate also balance, and the phase is blunt about the cost: the spread then
 * disappears into the pool as drift, so neither "what has the spread earned" nor "what is our
 * currency exposure" can be answered. {@code FEE_INCOME} answers the first only if the pool gives
 * up the mid amount and the difference is posted explicitly.
 *
 * <p><strong>The capture leg carries the {@code reservationId}</strong>, which routes it through
 * {@code PostingEngine}'s "DEBIT with a reservation decrements {@code reserved} only" rule. The
 * hold already moved the money out of {@code available} at quote time; a second decrement here is
 * the doubly-reduced-EUR bug the phase's Risks section points at, and it is invisible to the
 * zero-sum check.
 *
 * <p><strong>A rejection is not an exception.</strong> An expired reservation, an unknown wallet,
 * a missing system account: all of them commit and emit {@link EntryRejectedV1} keyed on the
 * ledger key {@code fxquote:<quoteId>}, which is how fx-service recognises its own rejection on a
 * topic carrying everyone's and runs its compensation - cancel the quote, release the hold, tell
 * the user. Throwing would retry then dead-letter a message that can never succeed and leave the
 * quote {@code CONSUMED} forever.
 */
@Component
public class FxExecutionRequestedEventHandler implements EventHandler<FxExecutionRequestedV1> {

    private static final Logger log = LoggerFactory.getLogger(FxExecutionRequestedEventHandler.class);

    private static final String OUTBOX_TOPIC = "ledger.entry.v1";

    private final LedgerAccountRepository ledgerAccounts;
    private final ReservationRepository reservations;
    private final ReservationService reservationService;
    private final OutboxAppender outboxAppender;

    public FxExecutionRequestedEventHandler(LedgerAccountRepository ledgerAccounts,
            ReservationRepository reservations, ReservationService reservationService,
            OutboxAppender outboxAppender) {
        this.ledgerAccounts = ledgerAccounts;
        this.reservations = reservations;
        this.reservationService = reservationService;
        this.outboxAppender = outboxAppender;
    }

    @Override
    public void handle(EventEnvelope<FxExecutionRequestedV1> envelope) {
        FxExecutionRequestedV1 event = envelope.payload();
        String idempotencyKey = ledgerIdempotencyKey(event.quoteId());

        Optional<Reservation> held = reservations.findById(event.reservationId());
        if (held.isEmpty()) {
            reject(event, idempotencyKey, event.buyLedgerAccountId(), EntryRejectionReason.RESERVATION_NOT_HELD,
                    "reservation " + event.reservationId() + " does not exist");
            return;
        }
        Reservation reservation = held.get();
        if (!reservation.getCurrency().equals(event.sellCurrency())) {
            // Impossible from a quote (the hold is placed on the quote's own sell wallet) and
            // therefore a malformed request rather than a business outcome - but rejecting it says
            // so, where posting it would build an entry that cannot balance.
            reject(event, idempotencyKey, reservation.getLedgerAccountId(), EntryRejectionReason.CURRENCY_MISMATCH,
                    "reservation " + event.reservationId() + " holds " + reservation.getCurrency()
                            + ", not the " + event.sellCurrency() + " this execution sells");
            return;
        }

        Optional<LedgerAccount> buyWallet = ledgerAccounts.findById(event.buyLedgerAccountId());
        if (buyWallet.isEmpty()) {
            reject(event, idempotencyKey, event.buyLedgerAccountId(), EntryRejectionReason.ACCOUNT_NOT_FOUND,
                    "no ledger_account " + event.buyLedgerAccountId() + " to credit");
            return;
        }

        List<PostingLine> counterLegs;
        try {
            counterLegs = counterLegsFor(event, buyWallet.get());
        } catch (MissingSystemAccountException ex) {
            // The system accounts are seeded per currency by a migration, so this is a deployment
            // problem rather than anything the user did - but it still has to come back as a
            // rejection, or the quote stays CONSUMED with the funds held until the hold expires.
            log.error("cannot post FX for quote {}: {}", event.quoteId(), ex.getMessage());
            reject(event, idempotencyKey, reservation.getLedgerAccountId(), EntryRejectionReason.ACCOUNT_NOT_FOUND,
                    ex.getMessage());
            return;
        }

        try {
            CaptureResult result = reservationService.captureWithLegs(event.reservationId(), event.sellMinor(),
                    JournalEntryType.FX, idempotencyKey, event.quoteId(), reserved -> counterLegs);
            log.info("posted FX entry {} for quote {}: {} {} -> {} {} ({} legs)", result.entry().getId(),
                    event.quoteId(), event.sellMinor(), event.sellCurrency(), event.clientBuyMinor(),
                    event.buyCurrency(), result.postings().size());
        } catch (ReservationNotFoundException | ReservationNotHeldException | CaptureExceedsHoldException ex) {
            // The hold was swept, released or already captured between fx-service accepting the
            // execution and this capture's transaction reaching it - the phase's "reservation expiry
            // racing execution". The re-check that catches it is inside the capture transaction, not
            // before it, which is the only place it can be authoritative. The funds are already back
            // in `available`, so there is nothing to undo here; fx-service cancels its quote.
            log.info("FX execution for quote {} lost the race with its own reservation: {}", event.quoteId(),
                    ex.getMessage());
            reject(event, idempotencyKey, reservation.getLedgerAccountId(),
                    EntryRejectionReason.RESERVATION_NOT_HELD, ex.getMessage());
        }
    }

    /**
     * Every leg except the capturing DEBIT, which {@link ReservationService#captureWithLegs} builds
     * from the reservation itself. The residual leg is present only when the three roundings
     * fx-service applied did not cancel, which is what makes the entry five legs or six rather than
     * always six with a zero. The fee leg is skipped on the same principle when the configured
     * spread is zero bps: the ledger has no zero-amount posting (see {@link PostingLine}), and an
     * exchange that earned nothing should not claim a revenue leg that says so.
     */
    private List<PostingLine> counterLegsFor(FxExecutionRequestedV1 event, LedgerAccount buyWallet) {
        LedgerAccount sellPool = systemAccount(LedgerAccountType.FX_POOL, SystemAccountRefs.FX_POOL,
                event.sellCurrency());
        LedgerAccount buyPool = systemAccount(LedgerAccountType.FX_POOL, SystemAccountRefs.FX_POOL,
                event.buyCurrency());

        List<PostingLine> legs = new ArrayList<>(5);
        legs.add(new PostingLine(sellPool.getId(), PostingDirection.CREDIT, event.sellMinor(),
                event.sellCurrency(), null));
        legs.add(new PostingLine(buyPool.getId(), PostingDirection.DEBIT, event.poolMidMinor(),
                event.buyCurrency(), null));
        legs.add(new PostingLine(buyWallet.getId(), PostingDirection.CREDIT, event.clientBuyMinor(),
                event.buyCurrency(), null));
        if (event.feeMinor() > 0) {
            LedgerAccount feeIncome = systemAccount(LedgerAccountType.FEE_INCOME, SystemAccountRefs.FEE_INCOME,
                    event.buyCurrency());
            legs.add(new PostingLine(feeIncome.getId(), PostingDirection.CREDIT, event.feeMinor(),
                    event.buyCurrency(), null));
        }
        if (event.pnlMinor() != 0) {
            LedgerAccount fxPnl = systemAccount(LedgerAccountType.FX_PNL, SystemAccountRefs.FX_PNL,
                    event.buyCurrency());
            // Signed on the wire, a direction here: amount_minor is always positive in the database
            // and the direction carries the sign.
            legs.add(new PostingLine(fxPnl.getId(),
                    event.pnlMinor() > 0 ? PostingDirection.CREDIT : PostingDirection.DEBIT,
                    Math.abs(event.pnlMinor()), event.buyCurrency(), null));
        }
        return legs;
    }

    private LedgerAccount systemAccount(LedgerAccountType type, String ownerRef, String currency) {
        return ledgerAccounts.findByOwnerRefAndTypeAndCurrency(ownerRef, type, currency)
                .orElseThrow(() -> new MissingSystemAccountException(
                        "no " + type + " account (" + ownerRef + ") for " + currency));
    }

    private void reject(FxExecutionRequestedV1 event, String idempotencyKey, UUID accountId,
            EntryRejectionReason reason, String detail) {
        log.warn("rejecting FX execution for quote {}: {} - {}", event.quoteId(), reason, detail);
        outboxAppender.append(OUTBOX_TOPIC, accountId.toString(),
                new EntryRejectedV1(idempotencyKey, accountId, reason.name(), event.quoteId().toString()));
    }

    /**
     * Mirrors {@code FxQuote}'s own key derivation in fx-service - the same string derived
     * independently on both sides of the topic from the only thing that crosses it, the quote id.
     * {@code PostingTransactionExecutor} independently requires this prefix on an {@code FX} entry,
     * and its uniqueness is what makes two concurrent deliveries of one execution post one entry.
     */
    private static String ledgerIdempotencyKey(UUID quoteId) {
        return "fxquote:" + quoteId;
    }

    /** A seeded system account is missing - see the {@code ACCOUNT_NOT_FOUND} rejection above. */
    private static final class MissingSystemAccountException extends RuntimeException {
        private MissingSystemAccountException(String message) {
            super(message);
        }
    }
}
