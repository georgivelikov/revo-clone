package com.revolutclone.notification.fanout;

import com.revolutclone.notification.domain.Notification;
import com.revolutclone.notification.domain.NotificationRepository;
import com.revolutclone.notification.ws.WebSocketConfig;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Step 4 of plans/phase-03-payments-and-ui.md's transfer saga: <q>notification-svc consumes payment
 * + ledger events → pushes over STOMP to {@code /user/{userId}/queue/events} for both parties.</q>
 * The one place in this service that touches the broker, so every consumer is testable without one
 * and the destination is spelled out exactly once.
 *
 * <p>{@link #record} writes the feed row and pushes it; {@link #push} pushes a transient status
 * frame that is deliberately not persisted (see {@link ClientEvent}).
 *
 * <p><strong>The row is written first, then the frame is sent.</strong> A frame the user sees but
 * never finds again in their feed is a worse failure than a feed row that arrives a poll later: the
 * client's own 5-second polling fallback exists precisely because a socket frame can be lost, while
 * nothing re-materialises a notification that was never stored. The push is not transactional -
 * there is no rolling a WebSocket frame back - which is another reason it goes last.
 */
@Component
public class NotificationFanOut {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanOut.class);

    private final NotificationRepository notifications;
    private final SimpMessagingTemplate broker;

    public NotificationFanOut(NotificationRepository notifications, SimpMessagingTemplate broker) {
        this.notifications = notifications;
        this.broker = broker;
    }

    /** Persists a feed entry and pushes it to its owner. */
    public void record(Notification notification, Long balanceAfterMinor) {
        notifications.saveAndFlush(notification);
        push(notification.getUserId(), ClientEvent.of(notification, balanceAfterMinor));
    }

    /**
     * Pushes a frame to one user's sockets - all of them, if they have the app open twice, which is
     * what {@code convertAndSendToUser} does for free once the session principal is right.
     *
     * <p>A user with no open socket is the normal case, not an error: the frame is dropped by the
     * broker and the UI reconciles from {@code GET /api/payments/{id}} or the persisted feed. That is
     * the eventual-consistency contract the phase is built around, so it is logged at debug, not
     * warn.
     */
    public void push(UUID userId, ClientEvent event) {
        log.debug("pushing {} for payment {} to user {}", event.type(), event.paymentId(), userId);
        broker.convertAndSendToUser(userId.toString(), WebSocketConfig.USER_QUEUE, event);
    }
}
