package com.revolutclone.card.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.revolutclone.card.authz.AuthorizationService;
import com.revolutclone.card.authz.FundingWalletUnknownException;
import com.revolutclone.card.fx.CardFxRateUnavailableException;
import com.revolutclone.card.ledger.LedgerUnavailableException;
import com.revolutclone.card.settlement.SettlementService;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationRequestedV1;
import com.revolutclone.contracts.events.ReversalRequestedV1;
import com.revolutclone.contracts.events.SettlementRequestedV1;
import com.revolutclone.messaging.api.TransientMessagingException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * plans/phase-07-resilience-and-scale.md task 3: "Audit every consumer: business rejections must
 * commit, not throw." The three consumers here are the other half of that audit - not a business
 * rejection among them, but each one's own dependency (a Kafka projection lagging, fx-service's rate
 * cache warming, ledger-service unreachable) throws a card-service-specific exception that every one
 * of {@code FundingWalletUnknownException}, {@code CardFxRateUnavailableException} and
 * {@code LedgerUnavailableException} documents, on its own javadoc, as "thrown so the message is
 * retried".
 *
 * <p>Before this task, none of the three was true: {@code DefaultErrorClassifier} does not recognise
 * any of them, so each defaulted to {@code FATAL} and went straight to the dead-letter topic on the
 * first attempt - a good card declined by a DLT entry nobody would think to look at, exactly the
 * poison-pill failure mode the retry chain exists to prevent, just arrived at from the opposite
 * direction (a transient condition treated as fatal, rather than a fatal one retried forever). These
 * tests pin the fix: the three consumers now convert each into {@link TransientMessagingException},
 * which {@code DefaultErrorClassifierTest} already proves the classifier sends into the retry chain.
 */
class TransientLedgerFailuresRetryTest {

    private static final String NETWORK_REF = "net-transient-0001";

    @Test
    void aFundingWalletNotYetProjectedIsRetriedNotDeadLettered() {
        AuthorizationService authorizations = mock(AuthorizationService.class);
        when(authorizations.authorize(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new FundingWalletUnknownException(UUID.randomUUID(), UUID.randomUUID()));
        AuthorizationRequestedEventHandler handler = new AuthorizationRequestedEventHandler(authorizations);

        assertThat(catchThrown(() -> handler.handle(authorizationRequest())))
                .isInstanceOf(TransientMessagingException.class)
                .hasCauseInstanceOf(FundingWalletUnknownException.class);
    }

    @Test
    void anFxRateCacheStillWarmingIsRetriedNotDeadLettered() {
        AuthorizationService authorizations = mock(AuthorizationService.class);
        when(authorizations.authorize(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new CardFxRateUnavailableException("no tick yet for EUR/USD"));
        AuthorizationRequestedEventHandler handler = new AuthorizationRequestedEventHandler(authorizations);

        assertThat(catchThrown(() -> handler.handle(authorizationRequest())))
                .isInstanceOf(TransientMessagingException.class)
                .hasCauseInstanceOf(CardFxRateUnavailableException.class);
    }

    @Test
    void aLedgerTheAuthorizationConsumerCannotReachIsRetriedNotDeadLettered() {
        AuthorizationService authorizations = mock(AuthorizationService.class);
        when(authorizations.authorize(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new LedgerUnavailableException("connection refused", null));
        AuthorizationRequestedEventHandler handler = new AuthorizationRequestedEventHandler(authorizations);

        assertThat(catchThrown(() -> handler.handle(authorizationRequest())))
                .isInstanceOf(TransientMessagingException.class)
                .hasCauseInstanceOf(LedgerUnavailableException.class);
    }

    @Test
    void aLedgerTheSettlementConsumerCannotReachIsRetriedNotDeadLettered() {
        SettlementService settlements = mock(SettlementService.class);
        when(settlements.settle(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new LedgerUnavailableException("read timeout", null));
        SettlementRequestedEventHandler handler = new SettlementRequestedEventHandler(settlements);

        assertThat(catchThrown(() -> handler.handle(settlementRequest())))
                .isInstanceOf(TransientMessagingException.class)
                .hasCauseInstanceOf(LedgerUnavailableException.class);
    }

    @Test
    void aLedgerTheReversalConsumerCannotReachIsRetriedNotDeadLettered() {
        SettlementService settlements = mock(SettlementService.class);
        when(settlements.reverse(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new LedgerUnavailableException("connection refused", null));
        ReversalRequestedEventHandler handler = new ReversalRequestedEventHandler(settlements);

        assertThat(catchThrown(() -> handler.handle(reversalRequest())))
                .isInstanceOf(TransientMessagingException.class)
                .hasCauseInstanceOf(LedgerUnavailableException.class);
    }

    private static Throwable catchThrown(Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable thrown) {
            return thrown;
        }
        throw new AssertionError("expected an exception, but none was thrown");
    }

    private static EventEnvelope<AuthorizationRequestedV1> authorizationRequest() {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), UUID.randomUUID().toString(),
                AuthorizationRequestedV1.class.getSimpleName(), 1,
                new AuthorizationRequestedV1(NETWORK_REF, "4000000000000002", 1_000L, "EUR", "Tesco", "5411", "IE"));
    }

    private static EventEnvelope<SettlementRequestedV1> settlementRequest() {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), UUID.randomUUID().toString(),
                SettlementRequestedV1.class.getSimpleName(), 1,
                new SettlementRequestedV1(NETWORK_REF, 1_000L, "EUR"));
    }

    private static EventEnvelope<ReversalRequestedV1> reversalRequest() {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), UUID.randomUUID().toString(),
                ReversalRequestedV1.class.getSimpleName(), 1, new ReversalRequestedV1(NETWORK_REF));
    }
}
