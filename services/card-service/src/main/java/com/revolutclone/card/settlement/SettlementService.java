package com.revolutclone.card.settlement;

import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.CardAuthorizationRepository;
import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.ledger.LedgerReservations;
import com.revolutclone.card.ledger.SettleFundsCommand;
import com.revolutclone.card.ledger.SettledFunds;
import com.revolutclone.contracts.events.AuthorizationReversedV1;
import com.revolutclone.contracts.events.AuthorizationSettledV1;
import com.revolutclone.contracts.events.ReversalRequestedV1;
import com.revolutclone.contracts.events.SettlementRequestedV1;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * plans/phase-05-cards.md's settlement table - the second half of the phase, and the half that finally
 * moves money:
 *
 * <pre>
 * | Exact settlement      | Capture the full hold -> journal entry CARD_SETTLEMENT |
 * | Partial (&lt; auth)      | Capture the lesser amount, release the difference      |
 * | Over-settlement (tip) | Capture the hold, post the excess directly - may      |
 * |                       | overdraw; allow it and flag                            |
 * | Reversal              | Release the hold, no posting                           |
 * </pre>
 *
 * <p><strong>Why the four cases are three code paths and not four.</strong> The first three differ
 * only in how the settled amount compares with the hold, so they are one method: the comparison is a
 * fact about two numbers, and branching on it up here would mean three nearly identical sequences to
 * keep in step. The reversal is genuinely different - it is the <em>absence</em> of an accounting
 * event, so it releases and posts nothing, and no amount is involved at all.
 *
 * <p><strong>The settled amount arrives in the merchant's currency and has to be converted, but not
 * re-rated.</strong> The choice recorded in {@code CardFxDetail} is to hold at the authorization's
 * rate and settle at the authorization's rate, which is what the holder was shown at the till. So the
 * conversion here is a proration against the authorization's own two amounts - see
 * {@link #billingAmountOf} - and not a fresh call to fx-service. An exact settlement therefore bills
 * exactly what was held, to the minor unit, with no rounding to argue about.
 *
 * <p><strong>Nothing here is a decline, and almost nothing here is an exception.</strong> By the time
 * a settlement arrives the money is gone as far as the scheme is concerned; there is nobody left to
 * refuse. A message about an authorization that does not exist, or one that has already reached a
 * terminal state, is answered with {@link SettlementOutcome#ignored} and committed - retrying it could
 * never help. What does throw is a ledger that cannot be reached, because that genuinely is unknown
 * rather than settled, and a message whose currency contradicts the authorization it names, because
 * that is a broken publisher.
 *
 * <p><strong>Idempotency is {@code networkRef} at three depths.</strong> The row's state is checked
 * here, the ledger's capture is keyed {@code cardauth:<networkRef>}, and the excess entry is keyed on
 * the same reference with a suffix. A resent settlement that gets past the state check - possible
 * across a consumer rebalance - still cannot post twice.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final CardAuthorizationRepository authorizations;
    private final CardRepository cards;
    private final LedgerReservations ledger;
    private final SettlementRecorder recorder;
    private final Clock clock;

    public SettlementService(CardAuthorizationRepository authorizations, CardRepository cards,
            LedgerReservations ledger, SettlementRecorder recorder, Clock clock) {
        this.authorizations = authorizations;
        this.cards = cards;
        this.ledger = ledger;
        this.recorder = recorder;
        this.clock = clock;
    }

    /** Exact, partial and over-settlement: capture what the merchant took, release or post the rest. */
    public SettlementOutcome settle(SettlementRequestedV1 request) {
        requireNetworkRef(request.networkRef());
        requireCurrency(request.networkRef(), request.currency());
        if (request.settledAmountMinor() <= 0) {
            // Zero is not "settled for nothing", it is a reversal wearing the wrong message type, and
            // letting it through would put a SETTLED row with no money behind it in the holder's feed.
            throw new IllegalArgumentException("settlement " + request.networkRef() + " is for "
                    + request.settledAmountMinor() + " minor units; a settlement for nothing is a reversal");
        }

        Optional<CardAuthorization> found = settleable(request.networkRef());
        if (found.isEmpty()) {
            return ignored(request.networkRef());
        }
        CardAuthorization authorization = found.get();
        // Normalised the same way the authorization chain normalised the merchant's currency, so a
        // publisher that sends "usd" is not told its settlement contradicts a "USD" authorization.
        requireSameCurrencyAsAuthorization(authorization, request.currency().toUpperCase(Locale.ROOT));

        long billingMinor = authorization.getBillingMinor();
        long settledBillingMinor = billingAmountOf(request.settledAmountMinor(), authorization);

        SettledFunds settled = ledger.settle(authorization.getReservationId(), new SettleFundsCommand(
                settledBillingMinor, feeShareOf(settledBillingMinor, authorization), request.networkRef()));

        Instant settledAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        authorization.markSettled(settledBillingMinor, settled.excessMinor(), settled.captureEntryId(),
                settled.excessEntryId(), settledAt);

        Card card = cardOf(authorization);
        recorder.record(authorization, new AuthorizationSettledV1(
                authorization.getId(),
                authorization.getCardId(),
                card.getUserId(),
                card.getAccountId(),
                card.getPanLast4(),
                authorization.getNetworkRef(),
                authorization.getMerchantName(),
                authorization.getMerchantMcc(),
                authorization.getMerchantCountry(),
                request.settledAmountMinor(),
                authorization.getCurrency(),
                settledBillingMinor,
                authorization.getBillingCurrency(),
                settled.releasedRemainderMinor(),
                settled.excessMinor(),
                settled.captureEntryId(),
                settled.excessEntryId(),
                authorization.getState().name()));

        if (settled.excessMinor() > 0) {
            // Flagged, not silently swallowed - the phase's own words. The column and the event field
            // are the durable half of that; this line is the half an operator finds first.
            log.warn("over-settlement on {}: merchant took {} where {} was authorized, so {} was posted "
                            + "beyond the hold and wallet {} may now be negative", authorization.getNetworkRef(),
                    settledBillingMinor, billingMinor, settled.excessMinor(), card.getAccountId());
        }
        return SettlementOutcome.applied(request.networkRef(), authorization.getState());
    }

    /** Reversal: release the hold, no posting. The balance goes back and no transaction ever existed. */
    public SettlementOutcome reverse(ReversalRequestedV1 request) {
        requireNetworkRef(request.networkRef());

        Optional<CardAuthorization> found = settleable(request.networkRef());
        if (found.isEmpty()) {
            return ignored(request.networkRef());
        }
        CardAuthorization authorization = found.get();

        ledger.release(authorization.getReservationId());
        authorization.markReversed();

        Card card = cardOf(authorization);
        recorder.record(authorization, new AuthorizationReversedV1(
                authorization.getId(),
                authorization.getCardId(),
                card.getUserId(),
                card.getAccountId(),
                card.getPanLast4(),
                authorization.getNetworkRef(),
                authorization.getMerchantName(),
                authorization.getBillingMinor(),
                authorization.getBillingCurrency()));

        return SettlementOutcome.applied(request.networkRef(), AuthorizationState.REVERSED);
    }

    /**
     * The authorization this message is about, if there is anything to be done to it.
     *
     * <p>Empty covers three genuinely different situations that all have the same answer: no such
     * {@code networkRef} (a settlement for an authorization we never made), a {@code DECLINED} one
     * (nothing was ever held, so there is nothing to capture or release), and one already
     * {@code SETTLED}, {@code PARTIALLY_SETTLED}, {@code REVERSED} or {@code EXPIRED} (a resend, or a
     * second message about a decision already concluded). The reason is logged; the message commits.
     */
    private Optional<CardAuthorization> settleable(String networkRef) {
        return authorizations.findByNetworkRef(networkRef)
                .filter(authorization -> authorization.getState() == AuthorizationState.APPROVED);
    }

    private SettlementOutcome ignored(String networkRef) {
        Optional<CardAuthorization> existing = authorizations.findByNetworkRef(networkRef);
        String reason = existing
                .map(authorization -> "authorization is already " + authorization.getState())
                .orElse("no authorization was ever made under this networkRef");
        log.warn("ignoring a card settlement message for {}: {}", networkRef, reason);
        return SettlementOutcome.ignored(networkRef, reason);
    }

    /**
     * The settled amount, in the wallet's currency, prorated from the authorization's own two amounts.
     *
     * <p>{@code billingMinor / amountMinor} <em>is</em> the authorization's rate, mark-up included,
     * already expressed as a ratio of two integers - so applying it needs no rate, no decimal
     * arithmetic and no second call to fx-service, and CLAUDE.md hard rule 1 stays intact. It
     * also makes the exact case exactly exact: {@code settled == amountMinor} gives back
     * {@code billingMinor} to the minor unit, which a re-derived rate could not guarantee.
     *
     * <p>A domestic authorization has the two amounts equal, so this is the identity function for it -
     * which is why there is no separate branch for the domestic case to drift out of step with.
     *
     * <p>Truncating rather than rounding half-up, and deliberately: the remainder is a fraction of one
     * minor unit, and on a partial settlement the direction that favours the holder is down.
     * {@code multiplyExact} rather than {@code *} so that an implausible amount fails loudly instead of
     * wrapping silently into a small positive number.
     */
    private static long billingAmountOf(long settledAmountMinor, CardAuthorization authorization) {
        long amountMinor = authorization.getAmountMinor();
        long billingMinor = authorization.getBillingMinor();
        if (settledAmountMinor == amountMinor) {
            return billingMinor;
        }
        return Math.multiplyExact(settledAmountMinor, billingMinor) / amountMinor;
    }

    /**
     * The part of the settled amount that is the card mark-up rather than the merchant's money -
     * {@code CREDIT system:fee-income} instead of {@code CREDIT system:card-settlement}. Zero for a
     * domestic authorization, which has no mark-up at all.
     *
     * <p>Prorated over the <em>captured</em> amount, not the settled one, and so capped at the mark-up
     * the holder was told about: a partial settlement earns proportionally less, and a tip earns none.
     * The issuer's charge was for converting the purchase that was authorized, and a merchant taking
     * more afterwards does not make that conversion worth more.
     */
    private static long feeShareOf(long settledBillingMinor, CardAuthorization authorization) {
        Long markupMinor = authorization.getFxMarkupMinor();
        if (markupMinor == null || markupMinor == 0L) {
            return 0L;
        }
        long billingMinor = authorization.getBillingMinor();
        long capturedMinor = Math.min(settledBillingMinor, billingMinor);
        return Math.multiplyExact(markupMinor, capturedMinor) / billingMinor;
    }

    private Card cardOf(CardAuthorization authorization) {
        return cards.findById(authorization.getCardId()).orElseThrow(() -> new IllegalStateException(
                "authorization " + authorization.getId() + " references card " + authorization.getCardId()
                        + ", which does not exist - card_authorization.card_id is a foreign key, so this "
                        + "cannot happen without the row having been deleted underneath us"));
    }

    /**
     * A settlement quoting an amount in a currency the authorization never named is a broken publisher,
     * not a business outcome: the amounts cannot be compared at all, so there is no safe interpretation
     * to fall back on. Thrown, so the message ends up dead-lettered rather than guessed at.
     */
    private static void requireSameCurrencyAsAuthorization(CardAuthorization authorization, String currency) {
        if (!authorization.getCurrency().equals(currency)) {
            throw new IllegalArgumentException("settlement " + authorization.getNetworkRef() + " is in "
                    + currency + " but the authorization was in " + authorization.getCurrency());
        }
    }

    /**
     * A malformed message is not an outcome - there is no authorization to conclude and no amount to
     * conclude it with. Thrown rather than ignored, so a broken publisher ends up visible on the
     * dead-letter topic instead of quietly dropping settlements nobody notices are missing.
     */
    private static void requireNetworkRef(String networkRef) {
        if (networkRef == null || networkRef.isBlank()) {
            throw new IllegalArgumentException("card settlement message carries no networkRef");
        }
    }

    private static void requireCurrency(String networkRef, String currency) {
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("settlement " + networkRef + " carries no ISO currency code");
        }
    }
}
