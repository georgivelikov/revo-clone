package com.revolutclone.card.testsupport;

import com.revolutclone.card.consumer.ReversalRequestedEventHandler;
import com.revolutclone.card.consumer.SettlementRequestedEventHandler;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.contracts.DomainEvent;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.ReversalRequestedV1;
import com.revolutclone.contracts.events.SettlementRequestedV1;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The fixture task 05.6's four tests share: {@link AuthorizationTestSupport}'s card on a funded wallet,
 * plus the two messages that conclude an authorization.
 *
 * <p>Every test here taps first and then settles, rather than writing an {@code APPROVED} row and a fake
 * hold by hand. That is deliberate: the phase's whole claim is about the <em>relationship</em> between
 * the two halves - that a hold moves available and not total, and that a settlement is what finally
 * moves total - and a fixture that manufactured the hold would be asserting arithmetic nobody performs.
 */
public abstract class SettlementTestSupport extends AuthorizationTestSupport {

    @Autowired
    protected SettlementRequestedEventHandler settlementConsumer;

    @Autowired
    protected ReversalRequestedEventHandler reversalConsumer;

    /** The merchant's batch arrives, in the merchant's own currency. */
    protected void settle(String networkRef, long settledAmountMinor) {
        settle(networkRef, settledAmountMinor, EUR);
    }

    protected void settle(String networkRef, long settledAmountMinor, String currency) {
        settlementConsumer.handle(settlementEnvelope(
                new SettlementRequestedV1(networkRef, settledAmountMinor, currency)));
    }

    /** The merchant will not be claiming the money after all. */
    protected void reverse(String networkRef) {
        reversalConsumer.handle(reversalEnvelope(new ReversalRequestedV1(networkRef)));
    }

    protected CardAuthorization authorizationOf(String networkRef) {
        return authorizations.findByNetworkRef(networkRef).orElseThrow();
    }

    /** The reservation the approval created - what a settlement or a reversal acts on. */
    protected UUID reservationOf(String networkRef) {
        return authorizationOf(networkRef).getReservationId();
    }

    private static EventEnvelope<SettlementRequestedV1> settlementEnvelope(SettlementRequestedV1 payload) {
        return envelopeOf(payload, SettlementRequestedV1.class.getSimpleName());
    }

    private static EventEnvelope<ReversalRequestedV1> reversalEnvelope(ReversalRequestedV1 payload) {
        return envelopeOf(payload, ReversalRequestedV1.class.getSimpleName());
    }

    private static <T extends DomainEvent> EventEnvelope<T> envelopeOf(T payload, String type) {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), UUID.randomUUID().toString(), type, 1, payload);
    }
}
