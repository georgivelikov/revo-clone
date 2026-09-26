package com.revolutclone.notification.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.NotificationPushV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.notification.domain.Notification;
import com.revolutclone.notification.fanout.NotificationFanOut;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pass-through path: {@link NotificationPushV1} carries no domain facts to interpret, because the
 * producer already decided what the user should be told. This service's job is delivery and history,
 * not editorial - so the title and body go through verbatim.
 *
 * <p>No read-model lookup either: the event names the user directly, which is the whole reason the
 * topic exists. Any later service with something to say to a user publishes here instead of growing
 * its own push path. The first real producer is phase 06's budget alerting.
 */
@Component
public class NotificationPushEventHandler implements EventHandler<NotificationPushV1> {

    private final NotificationFanOut fanOut;

    public NotificationPushEventHandler(NotificationFanOut fanOut) {
        this.fanOut = fanOut;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<NotificationPushV1> envelope) {
        NotificationPushV1 event = envelope.payload();
        fanOut.record(Notification.push(event.userId(), event.title(), event.body(), envelope.correlationId(),
                envelope.occurredAt()), null);
    }
}
