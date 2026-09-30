package com.revolutclone.simulator.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

/**
 * Dispatch logic only - {@link ChaosControlPlaneClient}, {@link ChaosPoisonPublisher} and
 * {@link ChaosDuplicator} each speak HTTP or Kafka directly and are exercised (or, for the thin
 * I/O wrappers, deliberately left untested - see {@code ChaosDuplicator}'s own javadoc) in their
 * own right; this proves {@link ChaosController} parses each lever's fields correctly and calls
 * the one collaborator it should, not the others.
 */
class ChaosControllerTest {

    private final ChaosControlPlaneClient controlPlane = mock(ChaosControlPlaneClient.class);
    private final ChaosPoisonPublisher poisonPublisher = mock(ChaosPoisonPublisher.class);
    private final ChaosDuplicator duplicator = mock(ChaosDuplicator.class);
    private final ChaosController controller = new ChaosController(controlPlane, poisonPublisher, duplicator);

    @Test
    void ledgerDelayParsesValueAndForwardsIt() {
        ChaosResult result = controller.apply(new ChaosCommand("ledger.delay", null, null, "5000", null));

        verify(controlPlane).setLedgerDelay(5000L);
        assertThat(result.lever()).isEqualTo("ledger.delay");
    }

    @Test
    void ledgerFailRateParsesValueAndForwardsIt() {
        controller.apply(new ChaosCommand("ledger.fail-rate", null, null, "30", null));

        verify(controlPlane).setLedgerFailRatePercent(30);
    }

    @Test
    void ledgerFailRateRejectsAValueOutOfRange() {
        assertThatThrownBy(() -> controller.apply(new ChaosCommand("ledger.fail-rate", null, null, "101", null)))
                .isInstanceOf(InvalidChaosCommandException.class);
        verifyNoInteractions(controlPlane);
    }

    @Test
    void ledgerRejectAllForwardsTheBoolean() {
        controller.apply(new ChaosCommand("ledger.reject-all", null, null, "true", null));

        verify(controlPlane).setLedgerRejectAll(true);
    }

    @Test
    void ledgerResetNeedsNoFields() {
        controller.apply(new ChaosCommand("ledger.reset", null, null, null, null));

        verify(controlPlane).resetLedger();
    }

    @Test
    void outboxPauseRequiresAService() {
        assertThatThrownBy(() -> controller.apply(new ChaosCommand("outbox.pause", null, null, null, null)))
                .isInstanceOf(InvalidChaosCommandException.class);
        verifyNoInteractions(controlPlane);
    }

    @Test
    void outboxPauseForwardsTheNamedService() {
        controller.apply(new ChaosCommand("outbox.pause", "payment-service", null, null, null));

        verify(controlPlane).pauseOutbox("payment-service");
    }

    @Test
    void fxDownTakesNoFields() {
        controller.apply(new ChaosCommand("fx.down", null, null, null, null));

        verify(controlPlane).setFxDown(true);
    }

    @Test
    void fxUpClearsIt() {
        controller.apply(new ChaosCommand("fx.up", null, null, null, null));

        verify(controlPlane).setFxDown(false);
    }

    @Test
    void poisonRequiresATopic() {
        assertThatThrownBy(() -> controller.apply(new ChaosCommand("poison", null, null, null, null)))
                .isInstanceOf(InvalidChaosCommandException.class);
        verifyNoInteractions(poisonPublisher);
    }

    @Test
    void poisonPublishesToTheNamedTopic() {
        controller.apply(new ChaosCommand("poison", null, "payment.event.v1", null, null));

        verify(poisonPublisher).poison("payment.event.v1");
    }

    @Test
    void duplicateRequiresATopicAndAPositiveCount() {
        assertThatThrownBy(() -> controller.apply(new ChaosCommand("duplicate", null, "ledger.entry.v1", null, 0)))
                .isInstanceOf(InvalidChaosCommandException.class);
        verifyNoInteractions(duplicator);
    }

    @Test
    void duplicateForwardsTopicAndCount() {
        controller.apply(new ChaosCommand("duplicate", null, "ledger.entry.v1", null, 100));

        verify(duplicator).duplicateLast("ledger.entry.v1", 100);
    }

    @Test
    void unknownLeverIsRejected() {
        assertThatThrownBy(() -> controller.apply(new ChaosCommand("not-a-real-lever", null, null, null, null)))
                .isInstanceOf(InvalidChaosCommandException.class);
        verifyNoInteractions(controlPlane, poisonPublisher, duplicator);
    }
}
