package com.revolutclone.notification.fanout;

import com.revolutclone.notification.rates.RateTickFrame;

/**
 * One envelope on {@link RedisFanOutRelay#CHANNEL} - the wire shape every {@code notification-service}
 * instance publishes and every instance (including the publisher) receives back.
 *
 * <p>Exactly two kinds travel here, matching this service's two STOMP destinations:
 * {@link #userEvent} for a per-user push ({@code /user/{userId}/queue/events}, see
 * {@code NotificationFanOut}) and {@link #rateTick} for the shared broadcast
 * ({@code /topic/rates}, see {@code RateTickBroadcaster}). Both payload fields are typed records
 * rather than one generic {@code Object} deliberately - Jackson can deserialize this envelope
 * with no class-name lookup at all, so a Redis channel shared with nothing else never needs to
 * trust a type name it read off the wire.
 */
public record FanOutMessage(Kind kind, String userId, ClientEvent event, RateTickFrame rate) {

    public enum Kind {
        USER_EVENT,
        RATE_TICK
    }

    public static FanOutMessage userEvent(String userId, ClientEvent event) {
        return new FanOutMessage(Kind.USER_EVENT, userId, event, null);
    }

    public static FanOutMessage rateTick(RateTickFrame rate) {
        return new FanOutMessage(Kind.RATE_TICK, null, null, rate);
    }
}
