package com.revolutclone.notification.ws;

import com.revolutclone.notification.config.NotificationProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Issues and redeems the single-use WebSocket tickets from plans/phase-03-payments-and-ui.md's
 * <em>WebSocket authentication - the gotcha</em>: <q>a single-use, 30-second, random ticket stored
 * in Redis. The client opens {@code ws://.../ws?ticket=...}; the handshake interceptor exchanges it
 * for the user id and deletes it.</q>
 *
 * <p>The phase lists three options and picks this one. The reasoning is worth keeping next to the
 * code, because the other two look easier:
 * <ul>
 *   <li><strong>A token in the query string lands in access logs</strong> - the gateway's, the
 *       browser's history, any proxy in between - and a JWT there is valid for minutes and reusable
 *       by anyone who reads it. A ticket in the same place is valid for thirty seconds and for one
 *       handshake.</li>
 *   <li><strong>A token in the STOMP {@code CONNECT} frame</strong> works, but leaves the socket
 *       open and unauthenticated until the first frame arrives, which means inventing a timeout to
 *       close sockets that never send one.</li>
 * </ul>
 *
 * <p><strong>Redis, not a local map</strong>, even though this service is pinned to one instance
 * today (see {@link WebSocketConfig}): the ticket is minted by an HTTP request and redeemed by a
 * WebSocket handshake, and the moment phase 07 lifts that pin those are two different processes. An
 * in-memory store would work perfectly right up to the first extra replica and then fail as a
 * one-in-N authentication failure, which is the worst possible way to find out.
 *
 * <p><strong>Single use is enforced by {@code GETDEL}, not by read-then-delete.</strong> Two
 * concurrent handshakes presenting the same ticket must not both succeed, and only an atomic
 * read-and-delete on the server guarantees that; {@code ValueOperations.getAndDelete} (Spring Data
 * Redis, since 2.6) issues exactly that command. A ticket is also never logged, here or anywhere
 * else - only the user id it resolved to.
 */
@Component
public class WebSocketTicketStore {

    /**
     * Namespaced away from the gateway's idempotency markers, which share this Redis instance.
     * Nothing else may read this keyspace: the value is an authenticated user id.
     */
    // Package-private rather than private only so WebSocketTicketTest can watch a ticket expire in
    // Redis without redeeming it - reading the key is the only way to test expiry as expiry rather
    // than as reuse.
    static final String KEY_PREFIX = "ws:ticket:";

    /** 256 bits from a CSPRNG. Guessing one inside its 30-second life is not a threat model. */
    private static final int TICKET_BYTES = 32;

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public WebSocketTicketStore(StringRedisTemplate redis, NotificationProperties properties) {
        this.redis = redis;
        this.ttl = properties.ticket().ttl();
    }

    /** Mints a ticket for an already-authenticated user - the caller has the JWT, not this class. */
    public IssuedTicket issue(String userId) {
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(KEY_PREFIX + ticket, userId, ttl);
        return new IssuedTicket(ticket, ttl);
    }

    /**
     * Exchanges a ticket for the user id it was issued to, consuming it. An empty result covers all
     * three failure modes the phase's test asks about - never issued, already used, expired -
     * deliberately without distinguishing them: the handshake's answer is the same 401 either way,
     * and telling a caller which of the three it was only helps the caller that should not be here.
     */
    public Optional<String> redeem(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redis.opsForValue().getAndDelete(KEY_PREFIX + ticket));
    }
}
