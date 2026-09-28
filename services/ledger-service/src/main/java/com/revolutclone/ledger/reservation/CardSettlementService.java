package com.revolutclone.ledger.reservation;

import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.ledger.domain.Reservation;
import com.revolutclone.ledger.domain.ReservationRepository;
import com.revolutclone.ledger.domain.SystemAccountRefs;
import com.revolutclone.ledger.posting.OverdraftPolicy;
import com.revolutclone.ledger.posting.PostEntryCommand;
import com.revolutclone.ledger.posting.PostEntryResult;
import com.revolutclone.ledger.posting.PostingEngine;
import com.revolutclone.ledger.posting.PostingLine;
import com.revolutclone.ledger.reservation.error.ReservationNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * plans/phase-05-cards.md's settlement table, as ledger operations:
 *
 * <pre>
 * | Exact settlement      | Capture the full hold -> journal entry CARD_SETTLEMENT |
 * | Partial (&lt; auth)      | Capture the lesser amount, release the difference      |
 * | Over-settlement (tip) | Capture the hold, post the excess directly - may      |
 * |                       | overdraw; allow it and flag                            |
 * | Reversal              | Release the hold, no posting                           |
 * </pre>
 *
 * <p>The first three are {@link #settle}; the fourth is {@link ReservationService#release} and needs
 * nothing new, which is the point - a reversal is the absence of an accounting event, not a special
 * kind of one.
 *
 * <p><strong>Why this lives in ledger-service and not in card-service.</strong> Three facts the
 * settlement posting depends on are the ledger's own and must not be mirrored: how much is actually
 * held (a card-service that believed otherwise could capture more than it held), which account
 * {@code system:card-settlement} is in this currency, and whether an entry is allowed to take a
 * wallet negative. card-service decides <em>that</em> an authorization settled and for how much;
 * this decides what that means in postings. It also makes the round trip one call instead of three,
 * which matters less here than on the authorization path but is free.
 *
 * <p><strong>Deliberately not {@code @Transactional}.</strong> It calls two already-transactional
 * operations in sequence - {@link ReservationService#captureWithLegs} and, only for an
 * over-settlement, {@link PostingEngine#postEntry}. The second is {@code @Retryable}, and calling a
 * retryable method from inside an open transaction would retry inside a transaction already marked
 * rollback-only on the first optimistic-lock conflict: the exact ordering bug
 * plans/phase-02-ledger.md's Risks section names and {@code PostingEngine}'s javadoc explains. So
 * each entry gets its own transaction, and a crash between the two leaves the capture posted and the
 * excess not. That is recoverable and the recovery is automatic: both entries are keyed on the
 * network's own reference, so the redelivered settlement finds the capture already done (the
 * idempotency branch in {@code captureWithLegs}) and posts the excess it missed. The alternative -
 * one transaction, no retry - would trade a self-healing gap for a hard failure under contention.
 */
@Service
public class CardSettlementService {

    private static final Logger log = LoggerFactory.getLogger(CardSettlementService.class);

    /**
     * The prefix {@code PostingTransactionExecutor} already requires of every
     * {@code CARD_SETTLEMENT} entry's key. The network's reference is the card flow's idempotency
     * anchor end to end, so the two entries a settlement can produce are keyed on it and on nothing
     * this service invented.
     */
    private static final String KEY_PREFIX = "cardauth:";

    /** The second entry needs a distinct key; it is a different entry about the same settlement. */
    private static final String EXCESS_KEY_SUFFIX = ":excess";

    private final ReservationRepository reservations;
    private final ReservationService reservationService;
    private final LedgerAccountRepository ledgerAccounts;
    private final PostingEngine postingEngine;

    public CardSettlementService(ReservationRepository reservations, ReservationService reservationService,
            LedgerAccountRepository ledgerAccounts, PostingEngine postingEngine) {
        this.reservations = reservations;
        this.reservationService = reservationService;
        this.ledgerAccounts = ledgerAccounts;
        this.postingEngine = postingEngine;
    }

    /**
     * Settles an authorization's hold for {@code settledMinor}, in the currency the hold is in.
     *
     * @param reservationId the hold the authorization created
     * @param settledMinor  what the merchant actually captured, in minor units of the hold's
     *                      currency. Less than the hold is a partial, more is an over-settlement,
     *                      equal is the ordinary case. Must be positive - a settlement for nothing
     *                      is a reversal, and callers must say so by releasing instead, so that
     *                      "the merchant took no money" and "the merchant took 0.00" cannot end up
     *                      as the same row
     * @param feeMinor      the slice of {@code settledMinor} that is the issuer's own revenue rather
     *                      than the merchant's money - card-service's FX mark-up, credited to
     *                      {@code system:fee-income} instead of {@code system:card-settlement}. Zero
     *                      for a domestic purchase. Bounded by the captured amount, not by
     *                      {@code settledMinor}: an over-settlement's excess is the merchant's, and
     *                      a tip does not earn the issuer a bigger mark-up
     * @throws IllegalArgumentException if the amounts are not self-consistent, or if a system
     *         account this settlement needs is missing for the hold's currency
     * @throws ReservationNotFoundException if no such hold exists
     */
    public CardSettlementResult settle(UUID reservationId, long settledMinor, long feeMinor, String networkRef) {
        if (settledMinor <= 0) {
            throw new IllegalArgumentException("settledMinor must be > 0, got " + settledMinor
                    + " - a settlement for nothing is a reversal; release the hold instead");
        }
        if (feeMinor < 0) {
            throw new IllegalArgumentException("feeMinor must be >= 0, got " + feeMinor);
        }
        if (networkRef == null || networkRef.isBlank()) {
            throw new IllegalArgumentException("networkRef must not be blank - it is the idempotency anchor");
        }

        // Read outside the capture's transaction only to plan the split. If the hold changed in
        // between, captureWithLegs rejects the amount against the row it reads itself, so this read
        // being stale is caught rather than trusted - there is no second version of the truth here.
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        long heldMinor = reservation.getAmountMinor();
        String currency = reservation.getCurrency();

        long capturedMinor = Math.min(settledMinor, heldMinor);
        long excessMinor = settledMinor - capturedMinor;
        if (feeMinor > capturedMinor) {
            throw new IllegalArgumentException("feeMinor " + feeMinor + " exceeds the captured amount "
                    + capturedMinor + " on reservation " + reservationId);
        }

        UUID cardSettlementAccountId = systemAccount(SystemAccountRefs.CARD_SETTLEMENT,
                LedgerAccountType.CARD_SETTLEMENT, currency);
        UUID feeIncomeAccountId = feeMinor > 0
                ? systemAccount(SystemAccountRefs.FEE_INCOME, LedgerAccountType.FEE_INCOME, currency)
                : null;

        // DEBIT the wallet for what was captured (the capturing leg captureWithLegs builds itself,
        // from the reservation, so this side cannot misstate the account or the currency), CREDIT
        // the acquirer's side of the house, and - when the authorization was a foreign-currency one -
        // CREDIT the mark-up to FEE_INCOME as its own leg. That last leg is what task 05.5 recorded
        // fx_markup_minor for: the phase's ForeignCurrencyAuthTest asks for "mark-up in FEE_INCOME",
        // and this is the posting that puts it there. The three legs balance by construction, since
        // the two credits are a partition of the debit.
        CaptureResult capture = reservationService.captureWithLegs(reservationId, capturedMinor,
                JournalEntryType.CARD_SETTLEMENT, KEY_PREFIX + networkRef, null,
                held -> {
                    List<PostingLine> legs = new ArrayList<>(2);
                    long merchantShare = capturedMinor - feeMinor;
                    if (merchantShare > 0) {
                        legs.add(new PostingLine(cardSettlementAccountId, PostingDirection.CREDIT,
                                merchantShare, held.getCurrency(), null));
                    }
                    if (feeMinor > 0) {
                        legs.add(new PostingLine(feeIncomeAccountId, PostingDirection.CREDIT, feeMinor,
                                held.getCurrency(), null));
                    }
                    return legs;
                });

        UUID excessEntryId = excessMinor > 0
                ? postExcess(reservation.getLedgerAccountId(), cardSettlementAccountId, excessMinor, currency,
                        networkRef)
                : null;

        return new CardSettlementResult(capture.entry().getId(), capture.capturedMinor(),
                capture.releasedRemainderMinor(), excessMinor, excessEntryId);
    }

    /**
     * The over-settlement leg: an ordinary {@code DEBIT wallet / CREDIT system:card-settlement} with
     * no {@code reservationId}, because no hold covers it - and with
     * {@link OverdraftPolicy#ALLOW}, because the money is already gone and refusing to record it
     * would only make the books wrong.
     *
     * <p>Logged at WARN: plans/phase-05-cards.md asks for the excess to be "flagged, not silently
     * swallowed", and a line an operator can find is half of that. The other half is the
     * {@code excessMinor} this returns to card-service, which records it on the authorization row
     * and publishes it.
     */
    private UUID postExcess(UUID walletAccountId, UUID cardSettlementAccountId, long excessMinor,
            String currency, String networkRef) {
        PostEntryResult result = postingEngine.postEntry(new PostEntryCommand(
                KEY_PREFIX + networkRef + EXCESS_KEY_SUFFIX,
                JournalEntryType.CARD_SETTLEMENT,
                null,
                List.of(
                        new PostingLine(walletAccountId, PostingDirection.DEBIT, excessMinor, currency, null),
                        new PostingLine(cardSettlementAccountId, PostingDirection.CREDIT, excessMinor, currency,
                                null)),
                OverdraftPolicy.ALLOW));

        if (!(result instanceof PostEntryResult.Posted posted)) {
            // Unreachable by insufficient funds, which is the whole reason ALLOW is passed above, so
            // a rejection here means a missing account or a currency mismatch: a bug, not an outcome.
            PostEntryResult.Rejected rejected = (PostEntryResult.Rejected) result;
            throw new IllegalStateException("over-settlement of " + excessMinor + " " + currency
                    + " for networkRef " + networkRef + " was rejected: " + rejected.reason() + " on account "
                    + rejected.accountId());
        }

        log.warn("over-settlement on networkRef {}: {} minor units of {} were captured beyond the "
                        + "authorization and posted directly to wallet {}; its available balance may now be "
                        + "negative", networkRef, excessMinor, currency, walletAccountId);
        return posted.entry().getId();
    }

    private UUID systemAccount(String ownerRef, LedgerAccountType type, String currency) {
        return ledgerAccounts.findByOwnerRefAndTypeAndCurrency(ownerRef, type, currency)
                .map(LedgerAccount::getId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no " + ownerRef + " account for " + currency + " - card settlement cannot be posted "
                                + "in a currency the system accounts were never seeded for"));
    }
}
