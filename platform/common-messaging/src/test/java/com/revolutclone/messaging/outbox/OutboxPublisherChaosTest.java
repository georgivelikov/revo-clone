package com.revolutclone.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.revolutclone.messaging.api.EventPublisher;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * plans/phase-07-resilience-and-scale.md's {@code outbox.pause} lever, in isolation from the
 * Testcontainers-backed {@link OutboxPublisherTest}: while {@link ChaosOutboxControl} is paused,
 * {@link OutboxPublisher} must not even attempt to lock a batch - the whole point of the
 * experiment is rows piling up untouched in the database, not a batch that is locked and
 * immediately released.
 */
class OutboxPublisherChaosTest {

    @Test
    void pausedPublisherNeverTouchesTheRepository() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        EventPublisher eventPublisher = mock(EventPublisher.class);
        ChaosOutboxControl chaosOutboxControl = new ChaosOutboxControl();
        chaosOutboxControl.pause();

        @SuppressWarnings("unchecked")
        ObjectProvider<OutboxPublisher> self = mock(ObjectProvider.class);
        OutboxPublisher publisher = new OutboxPublisher(repository, eventPublisher, Clock.systemUTC(),
                chaosOutboxControl, self);

        int published = publisher.pollAndPublish(100);

        assertThat(published).isZero();
        verifyNoInteractions(repository, eventPublisher);
    }
}
