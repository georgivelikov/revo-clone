package com.revolutclone.notification.consumer;

/**
 * One {@code processed_event} consumer group for the whole service, shared by every listener -
 * the same convention payment-service and ledger-service use. Stated once so a new listener
 * cannot invent a second group name and quietly re-notify a user with events the service has
 * already handled under the other name.
 *
 * <p>Public, not package-private: {@code com.revolutclone.notification.rates} (task 9's rate-tick
 * feed) is a listener too, kept in its own package alongside its cache and broadcaster rather than
 * folded into this one, exactly as fx-service keeps its own rate feed in its own {@code rate}
 * package rather than {@code consumer}.
 */
public final class NotificationConsumerGroup {

    public static final String NAME = "notification-service";

    private NotificationConsumerGroup() {
    }
}
