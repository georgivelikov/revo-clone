package com.revolutclone.card.authz;

import com.revolutclone.card.crypto.PanHasher;
import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.CardAuthorizationRepository;
import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.domain.CardState;
import com.revolutclone.card.domain.DeclineReason;
import com.revolutclone.card.ledger.HeldFunds;
import com.revolutclone.card.ledger.HoldFundsCommand;
import com.revolutclone.card.ledger.LedgerInsufficientFundsException;
import com.revolutclone.card.ledger.LedgerReservations;
import com.revolutclone.card.readmodel.FundingWallet;
import com.revolutclone.card.readmodel.FundingWalletResolver;
import com.revolutclone.contracts.events.AuthorizationApprovedV1;
import com.revolutclone.contracts.events.AuthorizationDeclinedV1;
import com.revolutclone.contracts.events.AuthorizationRequestedV1;
import com.revolutclone.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * plans/phase-05-cards.md's authorization flow, step by numbered step:
 *
 * <pre>
 * 1. resolve card by PAN hash          -> not found    -> DECLINED(CARD_NOT_FOUND)
 * 2. state == ACTIVE?                  -> frozen       -> DECLINED(CARD_FROZEN)
 * 3. expiry in the future?             -> expired      -> DECLINED(CARD_EXPIRED)
 * 4. FX if merchant currency != wallet -> fx-service rate, mark up          (task 05.5)
 * 5. limits: per-transaction, rolling daily, rolling monthly
 *                                      -> exceeded     -> DECLINED(LIMIT_EXCEEDED)
 * 6. ledger: HOLD billing amount, 7-day expiry
 *                                      -> insufficient -> DECLINED(INSUFFICIENT_FUNDS)
 * 7. -> AuthorizationApprovedV1   (or AuthorizationDeclinedV1 with the reason)
 * </pre>
 *
 * <p>Step 4 is the one the task list still assigns elsewhere - 05.5 converts a foreign-currency amount -
 * and it is marked in place below rather than appended later, because its <em>position</em> is part of
 * the chain: an unconverted amount held on the wrong-currency wallet would be wrong money, not merely a
 * missing feature. Step 5 is the same argument in the other direction. A limit evaluated after the hold
 * would let a refused purchase move a balance, so it sits between the amount being known and the money
 * being touched.
 *
 * <p><strong>Steps 5 to 7 run under {@link PerCardDecisionLock}.</strong> The limit window is computed
 * from committed rows, so the check and the row it produces have to be indivisible per card or two
 * simultaneous taps can both read a total that only one of them should have seen. See that class for why
 * the partition key is the real guarantee and this is the gap it leaves.
 *
 * <p><strong>Nothing here is a hold on a decision the network is still waiting for.</strong> The
 * phase's timing note - "steps 1-6 must complete in well under a second - a real network times out
 * around 2-5 s [...] this is why the decision cannot wait on a downstream service that might be
 * slow" - is why the card, the wallet and the limit window are all local reads, and why the single
 * remote call is the one whose answer the decision genuinely is.
 *
 * <p><strong>{@code networkRef} is the idempotency anchor, not {@code eventId}.</strong> A network
 * resend arrives as a new message with a new {@code eventId}, so {@code common-messaging}'s
 * {@code IdempotentHandler} would not recognise it; the unique index on
 * {@code card_authorization.network_ref} would, and does. This method therefore reads by
 * {@code networkRef} first and is deliberately <em>not</em> wrapped in the idempotent handler - two
 * keys for one fact, where the weaker one is checked first, only invites the belief that the weaker
 * one is enough. If two copies race past that read (possible only across a consumer rebalance, since
 * the topic is keyed by {@code cardId} and one thread owns a partition), the insert in
 * {@link AuthorizationRecorder} fails on the index and the message is retried - at which point the
 * read finds the decision and returns it.
 *
 * <p><strong>Not every failure is a decline.</strong> A decline is final and visible to the holder, so
 * it is reserved for the five reasons the flow names. A wallet this service has not projected yet
 * ({@link FundingWalletUnknownException}), a currency it cannot convert yet
 * ({@link ForeignCurrencyAuthorizationUnsupportedException}) and a ledger it cannot reach
 * ({@code LedgerUnavailableException}) all throw instead, so the message is retried rather than a good
 * card being refused. The hold is keyed on {@code networkRef}, which is what makes that retry safe.
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    /**
     * The ledger's key space is global, so the hold is keyed on the network's reference rather than on
     * anything this service invented - the same rule payment-service and fx-service apply to their own
     * ledger keys. A resent authorization lands on the same hold instead of a second one.
     */
    private static final String HOLD_KEY_PREFIX = "card-auth:";

    private final CardRepository cards;
    private final CardAuthorizationRepository authorizations;
    private final PanHasher panHasher;
    private final FundingWalletResolver wallets;
    private final LedgerReservations ledger;
    private final AuthorizationRecorder recorder;
    private final CardLimitEvaluator limits;
    private final PerCardDecisionLock perCardLock;
    private final CardAuthorizationProperties properties;
    private final Clock clock;

    public AuthorizationService(CardRepository cards, CardAuthorizationRepository authorizations,
            PanHasher panHasher, FundingWalletResolver wallets, LedgerReservations ledger,
            AuthorizationRecorder recorder, CardLimitEvaluator limits, PerCardDecisionLock perCardLock,
            CardAuthorizationProperties properties, Clock clock) {
        this.cards = cards;
        this.authorizations = authorizations;
        this.panHasher = panHasher;
        this.wallets = wallets;
        this.ledger = ledger;
        this.recorder = recorder;
        this.limits = limits;
        this.perCardLock = perCardLock;
        this.properties = properties;
        this.clock = clock;
    }

    public AuthorizationDecision authorize(AuthorizationRequestedV1 request) {
        validate(request);

        Optional<CardAuthorization> alreadyDecided = authorizations.findByNetworkRef(request.networkRef());
        if (alreadyDecided.isPresent()) {
            CardAuthorization existing = alreadyDecided.get();
            // Nothing is republished. The row and its event were written in one transaction, so an
            // existing row means the event exists too; announcing the same approval twice would fire a
            // second push and a second pending row at the holder.
            log.info("networkRef {} was already decided as {}; not deciding it again",
                    request.networkRef(), existing.getState());
            return AuthorizationDecision.of(existing);
        }

        // Truncated to what timestamptz stores, so the row and every window computed from it agree.
        Instant decidedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String currency = request.currency().toUpperCase(Locale.ROOT);

        // 1. resolve the card by PAN hash - never by last4, which is not unique (CardLookupByPanHashTest).
        Optional<Card> resolved = cards.findByPanHash(panHasher.hash(request.pan()));
        if (resolved.isEmpty()) {
            return declineUnknownCard(request, currency);
        }
        Card card = resolved.get();

        // 2. the card has to be usable. A frozen card declining instantly is the phase's own demo.
        if (card.getState() != CardState.ACTIVE) {
            return decline(card, request, currency, null, null, reasonFor(card.getState()), decidedAt);
        }

        // 3. and unexpired - valid through the last instant of its expiry month, as an embossed card is.
        if (hasExpired(card, decidedAt)) {
            return decline(card, request, currency, null, null, DeclineReason.CARD_EXPIRED, decidedAt);
        }

        FundingWallet wallet = wallets.resolve(card.getAccountId())
                .orElseThrow(() -> new FundingWalletUnknownException(card.getId(), card.getAccountId()));

        // 4. FX, when the merchant charges in something other than the wallet's currency (task 05.5:
        //    fx-service's rate plus the card mark-up, applied separately from the exchange spread).
        if (!wallet.currency().equals(currency)) {
            throw new ForeignCurrencyAuthorizationUnsupportedException(currency, wallet.currency());
        }
        // Money from here on, not a bare long: the billing amount is about to be compared against limits
        // in a currency of their own, and Money is what makes a mismatch impossible to do quietly.
        Money billing = Money.of(request.amountMinor(), wallet.currency());

        // Steps 5 to 7 are one indivisible decision per card: the limit window is read from committed
        // rows, so an interleaved second tap that checks before this one records would see a total that
        // is already out of date. See PerCardDecisionLock.
        return perCardLock.decide(card.getId(),
                () -> decideWithinLimits(card, request, currency, billing, wallet, decidedAt));
    }

    private AuthorizationDecision decideWithinLimits(Card card, AuthorizationRequestedV1 request,
            String currency, Money billing, FundingWallet wallet, Instant decidedAt) {

        long billingMinor = billing.minor();
        String billingCurrency = billing.currency().getCurrencyCode();

        // 5. limits: per-transaction, then the rolling daily and monthly windows over card_authorization.
        //    Before the hold, because a purchase that is refused must not move a balance.
        Optional<LimitBreach> breach = limits.evaluate(card.getId(), billing, decidedAt);
        if (breach.isPresent()) {
            log.info("declining authorization {} on card {}: {}", request.networkRef(), card.getId(),
                    breach.get().describe());
            return decline(card, request, currency, billingMinor, billingCurrency,
                    DeclineReason.LIMIT_EXCEEDED, decidedAt);
        }

        // 6. hold the billing amount. The one remote call in the chain, and the one whose answer the
        //    decision is: available balance lives in the ledger and must not be mirrored here.
        UUID authorizationId = UUID.randomUUID();
        HeldFunds held;
        try {
            held = ledger.hold(new HoldFundsCommand(
                    wallet.ledgerAccountId(),
                    billingMinor,
                    billingCurrency,
                    authorizationId,
                    HOLD_KEY_PREFIX + request.networkRef(),
                    decidedAt.plus(properties.holdTtl())));
        } catch (LedgerInsufficientFundsException ex) {
            log.info("declining authorization {} on card {}: {}", request.networkRef(), card.getId(),
                    ex.getMessage());
            return decline(card, request, currency, billingMinor, billingCurrency,
                    DeclineReason.INSUFFICIENT_FUNDS, decidedAt);
        }

        // 7. approve. Note what is NOT here: no posting, no journal entry, no ledger_entry_id. The money
        //    is held and no accounting has happened - which is the whole reason this phase exists.
        CardAuthorization approved = CardAuthorization.approved(authorizationId, card.getId(),
                request.networkRef(), request.merchantName(), request.merchantMcc(), request.merchantCountry(),
                request.amountMinor(), currency, billingMinor, billingCurrency, held.id(), decidedAt);
        recorder.record(approved, new AuthorizationApprovedV1(
                authorizationId,
                card.getId(),
                card.getUserId(),
                card.getAccountId(),
                card.getPanLast4(),
                request.networkRef(),
                request.merchantName(),
                request.merchantMcc(),
                request.merchantCountry(),
                request.amountMinor(),
                currency,
                billingMinor,
                billingCurrency,
                held.id()));

        return AuthorizationDecision.approved(authorizationId, held.id());
    }

    private AuthorizationDecision decline(Card card, AuthorizationRequestedV1 request, String currency,
            Long billingMinor, String billingCurrency, DeclineReason reason, Instant decidedAt) {

        UUID authorizationId = UUID.randomUUID();
        CardAuthorization declined = CardAuthorization.declined(authorizationId, card.getId(),
                request.networkRef(), request.merchantName(), request.merchantMcc(), request.merchantCountry(),
                request.amountMinor(), currency, billingMinor, billingCurrency, reason, decidedAt);

        recorder.record(declined, new AuthorizationDeclinedV1(
                authorizationId,
                card.getId(),
                card.getUserId(),
                card.getPanLast4(),
                request.networkRef(),
                request.merchantName(),
                request.merchantMcc(),
                request.merchantCountry(),
                request.amountMinor(),
                currency,
                reason.name()));

        return AuthorizationDecision.declined(authorizationId, reason);
    }

    private AuthorizationDecision declineUnknownCard(AuthorizationRequestedV1 request, String currency) {
        // The PAN itself is never logged, here or anywhere: only that one was presented and matched
        // nothing. See plans/phase-05-cards.md's PAN handling note and PanNeverLoggedTest (task 05.11).
        log.warn("declining authorization {} from {}: the presented card number matches no issued card",
                request.networkRef(), request.merchantName());

        recorder.recordUnknownCard(request.networkRef(), new AuthorizationDeclinedV1(
                null,
                null,
                null,
                null,
                request.networkRef(),
                request.merchantName(),
                request.merchantMcc(),
                request.merchantCountry(),
                request.amountMinor(),
                currency,
                DeclineReason.CARD_NOT_FOUND.name()));

        return AuthorizationDecision.declined(null, DeclineReason.CARD_NOT_FOUND);
    }

    /**
     * A card is good through the last instant of its expiry month - "valid thru 12/30" means the whole
     * of December 2030, not the first of it.
     */
    private static boolean hasExpired(Card card, Instant now) {
        Instant expiresAt = YearMonth.of(card.getExpiryYear(), card.getExpiryMonth())
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        return !now.isBefore(expiresAt);
    }

    private static DeclineReason reasonFor(CardState state) {
        return state == CardState.CANCELLED ? DeclineReason.CARD_CANCELLED : DeclineReason.CARD_FROZEN;
    }

    /**
     * A malformed request is not a decline either - there is nobody to decline to. Thrown rather than
     * recorded so the message ends up on the dead-letter topic, where a broken publisher belongs.
     */
    private static void validate(AuthorizationRequestedV1 request) {
        if (request.networkRef() == null || request.networkRef().isBlank()) {
            throw new IllegalArgumentException("authorization request carries no networkRef");
        }
        if (request.pan() == null || request.pan().isBlank()) {
            throw new IllegalArgumentException("authorization request " + request.networkRef()
                    + " carries no card number");
        }
        if (request.amountMinor() <= 0) {
            throw new IllegalArgumentException("authorization request " + request.networkRef()
                    + " is for " + request.amountMinor() + " minor units");
        }
        if (request.currency() == null || request.currency().length() != 3) {
            throw new IllegalArgumentException("authorization request " + request.networkRef()
                    + " carries no ISO currency code");
        }
    }
}
