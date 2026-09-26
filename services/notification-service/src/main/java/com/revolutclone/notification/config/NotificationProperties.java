package com.revolutclone.notification.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about this service that a deployment gets to decide. Bound from
 * {@code revolut.notifications.*} in {@code application.yml}, where every value comes from an
 * environment variable (CLAUDE.md rule 9).
 *
 * @param websocket the handshake endpoint and the origins allowed to reach it
 * @param ticket    the single-use WebSocket ticket's lifetime
 * @param feed      how much persisted history {@code GET /api/notifications} returns
 */
@ConfigurationProperties(prefix = "revolut.notifications")
public record NotificationProperties(WebSocket websocket, Ticket ticket, Feed feed) {

    /**
     * @param path           the WebSocket handshake path, e.g. {@code /ws}
     * @param allowedOrigins browser origins allowed to open the socket. <strong>CORS on the
     *                       WebSocket upgrade is a separate mechanism from HTTP CORS</strong> (the
     *                       gateway owns that one), so the origin has to be named here too -
     *                       plans/phase-03-payments-and-ui.md flags this as one round of expected
     *                       confusion.
     */
    public record WebSocket(String path, List<String> allowedOrigins) {
    }

    /**
     * @param ttl how long an issued ticket stays redeemable. 30 seconds per the phase spec:
     *            long enough for the client to open a socket, short enough that a ticket which
     *            leaked into a log or a proxy is already worthless. Single-use is enforced
     *            separately, by redeeming with an atomic {@code GETDEL}.
     */
    public record Ticket(Duration ttl) {
    }

    /**
     * @param pageSize how many notifications the feed endpoint returns, newest first
     */
    public record Feed(int pageSize) {
    }
}
