package com.revolutclone.notification.ws;

import com.revolutclone.notification.config.NotificationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over a native WebSocket at {@code /ws} - no SockJS, per
 * plans/phase-03-payments-and-ui.md's {@code ### notification-service}. Every browser this project
 * targets speaks WebSocket; SockJS exists to fall back for ones that do not, at the cost of extra
 * endpoints, extra state and an iframe.
 *
 * <p>Three settings here are load-bearing and each has a silent failure mode:
 * <ul>
 *   <li><strong>{@link MessageBrokerRegistry#setUserDestinationPrefix}</strong> plus a
 *       {@code Principal} resolved during the handshake ({@link TicketPrincipalHandshakeHandler}) is
 *       what makes {@code /user/{userId}/queue/events} work at all. The phase's Risks &amp; gotchas
 *       names this pair explicitly: "silent non-delivery is the usual symptom of getting this
 *       wrong". {@code /user} is also Spring's default, stated here rather than inherited because
 *       the fan-out's addressing depends on it.</li>
 *   <li><strong>{@code setAllowedOrigins}</strong> - CORS on the WebSocket upgrade is a different
 *       mechanism from HTTP CORS, which the gateway owns, so the browser's origin has to be named
 *       here too or the handshake is refused with a 403 that looks nothing like a CORS problem.</li>
 *   <li><strong>The interceptor and the handshake handler are two halves of one mechanism.</strong>
 *       The interceptor redeems the ticket and refuses the handshake; the handler turns the redeemed
 *       user id into the session principal. Registering only the first yields an authenticated
 *       socket that can never be sent to.</li>
 * </ul>
 *
 * <h2>This service cannot run more than one replica</h2>
 *
 * <p>{@code enableSimpleBroker} is an in-memory broker, and STOMP sessions live in this JVM's
 * memory. With N replicas a user's socket may be held by a different instance than the one whose
 * Kafka consumer received their event, and that instance's {@code convertAndSendToUser} finds no
 * session and drops the push - silently, and only for some users, some of the time.
 *
 * <p><strong>That is a deployment constraint, not only a scaling one</strong>, and the phase says to
 * write it down rather than pretend it is not there: notification-service is pinned to exactly one
 * instance on a laptop, in ECS and on Kubernetes alike, while every other service in this project is
 * stateless and horizontally scalable from the day it was written. Phase 07 task 9 lifts the pin
 * with a Redis pub/sub fan-out between instances (the cheap answer; an external STOMP broker via
 * {@code enableStompBrokerRelay} is the proper one). Until then, treat the single replica as a known
 * constraint with a date on it.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Where the fan-out sends and the browser subscribes; see {@code NotificationFanOut}. */
    public static final String USER_QUEUE = "/queue/events";

    /**
     * <strong>The trailing slash is required.</strong> Spring's documented default is {@code "/user/"},
     * and unlike {@code setApplicationDestinationPrefixes} - whose javadoc promises that "prefixes
     * that do not have a trailing slash will have one automatically appended" - the user destination
     * prefix makes no such promise. {@code SimpMessagingTemplate} concatenates prefix + user name +
     * destination, so {@code "/user"} produces {@code /user<uuid>/queue/events}, which resolves to no
     * session and is dropped without an error. Cost one debugging round; see
     * docs/spring-boot-4-notes.md.
     */
    private static final String USER_DESTINATION_PREFIX = "/user/";

    private final NotificationProperties properties;
    private final TicketHandshakeInterceptor ticketHandshakeInterceptor;

    public WebSocketConfig(NotificationProperties properties, TicketHandshakeInterceptor ticketHandshakeInterceptor) {
        this.properties = properties;
        this.ticketHandshakeInterceptor = ticketHandshakeInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(properties.websocket().path())
                .setAllowedOrigins(properties.websocket().allowedOrigins().toArray(String[]::new))
                .addInterceptors(ticketHandshakeInterceptor)
                .setHandshakeHandler(new TicketPrincipalHandshakeHandler());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Only /queue: this service pushes to individual users and has no broadcast destination yet.
        registry.enableSimpleBroker("/queue");
        registry.setUserDestinationPrefix(USER_DESTINATION_PREFIX);
        // No setApplicationDestinationPrefixes on purpose - there is no @MessageMapping here. The
        // socket is one-way by design: everything a client wants to *do* goes over HTTP through the
        // gateway, where it is authenticated, rate-limited and idempotent. A writable socket would
        // be a second, weaker door into the same money.
    }
}
