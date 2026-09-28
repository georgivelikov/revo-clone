package com.revolutclone.card.settlement;

import com.revolutclone.card.authz.CardAuthorizationProperties;
import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.CardAuthorizationRepository;
import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.ledger.LedgerRejectedSettlementException;
import com.revolutclone.card.ledger.LedgerReservations;
import com.revolutclone.card.ledger.LedgerUnavailableException;
import com.revolutclone.contracts.events.AuthorizationExpiredV1;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The last row of plans/phase-05-cards.md's settlement table: <em>"Expiry (7 days, no settlement) -
 * sweep releases the hold."</em> The one outcome nobody sends a message about, which is why it needs
 * a clock of its own.
 *
 * <p><strong>Why this exists when the ledger already expires the hold.</strong> The hold was created
 * with {@code expiresAt = authorizedAt + holdTtl} and ledger-service sweeps on that column without
 * any help - so the <em>money</em> comes back whether or not this class ever runs, which is exactly
 * the property that lets the ledger survive a card-service that never returns. What the ledger cannot
 * do is conclude the <em>authorization</em>: left alone, {@code card_authorization} would still say
 * {@code APPROVED}, the holder's feed would show a pending row against a hold that no longer exists,
 * and every limit window would go on counting spend that was never spent. So the ledger's sweep is
 * the backstop and this one is the owner, which is the same division fx-service's
 * {@code QuoteExpirySweep} makes for quotes.
 *
 * <p><strong>The two sweeps race, on purpose, and the race is harmless.</strong> Both are due at the
 * same instant, so either may reach a given hold first. That is why this asks the ledger to
 * {@code expire} rather than to {@code release}: expiry is idempotent on both terminal states that
 * mean the funds are back, so "the ledger got there first" is an ordinary success and not an error to
 * be recovered from. Asking at all - rather than assuming the ledger will get to it - is what makes
 * the funds available at the deadline instead of up to a sweep interval later, and what means this
 * class never marks a row {@code EXPIRED} on the strength of something it has not confirmed.
 *
 * <p><strong>A failure stops one authorization, not the cycle.</strong> Each row is concluded on its
 * own, so an unreachable ledger leaves that row {@code APPROVED} for the next cycle to retry rather
 * than abandoning the rest of the batch. The one failure that is not retried into eventual success is
 * a hold the ledger says was captured: that means a settlement posted and the row never recorded it,
 * which is a genuine inconsistency and is logged as one - expiring it would tell the holder their
 * money came back when it did not.
 */
@Component
public class AuthorizationExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationExpirySweep.class);

    private final CardAuthorizationRepository authorizations;
    private final CardRepository cards;
    private final LedgerReservations ledger;
    private final SettlementRecorder recorder;
    private final CardAuthorizationProperties properties;
    private final Clock clock;

    public AuthorizationExpirySweep(CardAuthorizationRepository authorizations, CardRepository cards,
            LedgerReservations ledger, SettlementRecorder recorder, CardAuthorizationProperties properties,
            Clock clock) {
        this.authorizations = authorizations;
        this.cards = cards;
        this.ledger = ledger;
        this.recorder = recorder;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${revolut.card.authorization.expiry-sweep-delay-ms:60000}")
    public void sweepExpiredAuthorizations() {
        sweepDueAsOf(clock.instant());
    }

    /**
     * Exposed with an explicit {@code asOf} instant, like {@code ReservationService}'s own sweep and
     * fx-service's, so seven days can be reached without waiting seven days or moving a clock bean.
     * Task 05.8's simulated day is the same idea from the other end - this is what it will advance
     * into.
     *
     * @return how many authorizations this call actually concluded
     */
    public int sweepDueAsOf(Instant asOf) {
        Instant cutoff = asOf.minus(properties.holdTtl());
        List<CardAuthorization> due = authorizations
                .findByStateAndAuthorizedAtLessThanEqualOrderByAuthorizedAt(AuthorizationState.APPROVED, cutoff);

        int expired = 0;
        for (CardAuthorization authorization : due) {
            if (expire(authorization)) {
                expired++;
            }
        }
        return expired;
    }

    /** One authorization concluded: the hold given back first, then the row and its event. */
    private boolean expire(CardAuthorization authorization) {
        try {
            ledger.expire(authorization.getReservationId());
        } catch (LedgerUnavailableException ex) {
            // Unknown, not failed. The row stays APPROVED and the next cycle finds it again.
            log.warn("could not expire hold {} for authorization {}; leaving it for the next sweep: {}",
                    authorization.getReservationId(), authorization.getNetworkRef(), ex.getMessage());
            return false;
        } catch (LedgerRejectedSettlementException ex) {
            // The hold was captured: money moved, and this row was never told. Retrying cannot fix
            // that, so it is said out loud rather than swept under an EXPIRED state that would claim
            // the holder got their money back.
            log.error("hold {} for authorization {} could not be expired because the ledger has already "
                            + "captured it - a settlement posted that this row never recorded: {}",
                    authorization.getReservationId(), authorization.getNetworkRef(), ex.getMessage());
            return false;
        }

        Card card = cards.findById(authorization.getCardId()).orElseThrow(() -> new IllegalStateException(
                "authorization " + authorization.getId() + " references card " + authorization.getCardId()
                        + ", which does not exist - card_authorization.card_id is a foreign key"));

        authorization.markExpired();
        recorder.record(authorization, new AuthorizationExpiredV1(
                authorization.getId(),
                authorization.getCardId(),
                card.getUserId(),
                card.getAccountId(),
                card.getPanLast4(),
                authorization.getNetworkRef(),
                authorization.getMerchantName(),
                authorization.getBillingMinor(),
                authorization.getBillingCurrency()));

        log.info("authorization {} on card {} expired unsettled after {}; {} {} is available again",
                authorization.getNetworkRef(), authorization.getCardId(), properties.holdTtl(),
                authorization.getBillingMinor(), authorization.getBillingCurrency());
        return true;
    }
}
