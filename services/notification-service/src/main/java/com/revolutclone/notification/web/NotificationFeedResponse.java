package com.revolutclone.notification.web;

import com.revolutclone.notification.domain.Notification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The feed plus the badge. Both in one response because the UI needs both at once and a second
 * round-trip for a number would be silly.
 *
 * @param notifications newest first
 * @param unreadCount   across the user's whole history, not just this page - the badge is a total
 */
public record NotificationFeedResponse(List<Item> notifications, long unreadCount) {

    /**
     * Deliberately the same field names as the socket frame's, minus the transient-status fields, so
     * the client can render a polled row and a pushed row with one component.
     */
    public record Item(
            UUID id,
            String type,
            Instant occurredAt,
            String title,
            String body,
            UUID paymentId,
            Long amountMinor,
            String currency,
            boolean unread,
            String correlationId) {

        static Item from(Notification notification) {
            return new Item(
                    notification.getId(),
                    notification.getType().name(),
                    notification.getCreatedAt(),
                    notification.getTitle(),
                    notification.getBody(),
                    notification.getPaymentId(),
                    notification.getAmountMinor(),
                    notification.getCurrency(),
                    notification.isUnread(),
                    notification.getCorrelationId());
        }
    }

    public static NotificationFeedResponse of(List<Notification> notifications, long unreadCount) {
        return new NotificationFeedResponse(notifications.stream().map(Item::from).toList(), unreadCount);
    }
}
