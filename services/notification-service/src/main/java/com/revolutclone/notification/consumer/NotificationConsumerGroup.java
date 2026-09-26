package com.revolutclone.notification.consumer;

/**
 * One {@code processed_event} consumer group for the whole service, shared by every listener here -
 * the same convention payment-service and ledger-service use. Stated once so a new listener cannot
 * invent a second group name and quietly re-notify a user with events the service has already
 * handled under the other name.
 */
final class NotificationConsumerGroup {

    static final String NAME = "notification-service";

    private NotificationConsumerGroup() {
    }
}
