package com.revolutclone.card.consumer;

import com.revolutclone.card.authz.AuthorizationDecision;
import com.revolutclone.card.authz.AuthorizationService;
import com.revolutclone.card.authz.FundingWalletUnknownException;
import com.revolutclone.card.fx.CardFxRateUnavailableException;
import com.revolutclone.card.ledger.LedgerUnavailableException;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationRequestedV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.messaging.api.TransientMessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The business half of the authorization consumer: unwraps the envelope, runs the decision chain, and
 * says what was decided. Kafka binding lives in {@link CardAuthorizationKafkaListener}, so this is
 * testable with no broker at all - which is how all three of this task's tests drive it.
 *
 * <p><strong>{@link FundingWalletUnknownException}, {@link CardFxRateUnavailableException} and
 * {@link LedgerUnavailableException} are converted to {@link TransientMessagingException} here.</strong>
 * Each already documents, on its own javadoc, that {@code AuthorizationService} throws it so "the
 * message is retried" - but none of the three is a type
 * {@code common-messaging}'s {@code DefaultErrorClassifier} recognises, and that classifier's own
 * javadoc says plainly that a framework-specific transient signal it cannot see must be converted at the
 * call site or it defaults to {@code FATAL} - straight to the dead-letter topic on the first attempt,
 * the exact poison-pill outcome the retry chain exists to avoid. This is the fix
 * plans/phase-07-resilience-and-scale.md's task 3 (audit every consumer) exists to make: these three are
 * not business rejections - {@code LedgerInsufficientFundsException} is the only one of those, and
 * {@code AuthorizationService} already turns it into a commit, never a throw - they are this service's
 * own dependencies not being caught up yet, and belong in the retry chain, not the DLT.
 */
@Component
public class AuthorizationRequestedEventHandler implements EventHandler<AuthorizationRequestedV1> {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationRequestedEventHandler.class);

    private final AuthorizationService authorizations;

    public AuthorizationRequestedEventHandler(AuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    @Override
    public void handle(EventEnvelope<AuthorizationRequestedV1> envelope) {
        AuthorizationRequestedV1 request = envelope.payload();
        AuthorizationDecision decision;
        try {
            decision = authorizations.authorize(request);
        } catch (FundingWalletUnknownException | CardFxRateUnavailableException | LedgerUnavailableException ex) {
            throw new TransientMessagingException(
                    "authorization " + request.networkRef() + " could not be decided yet: " + ex.getMessage(), ex);
        }

        // Never the PAN - only the network's reference, which is what an operator would search by.
        log.info("authorization {} at {}: {}{}", request.networkRef(), request.merchantName(),
                decision.state(),
                decision.isApproved() ? " (reservation " + decision.reservationId() + ")"
                        : " (" + decision.declineReason() + ")");
    }
}
