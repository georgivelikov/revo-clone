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
 * <h2>Why every push goes through Redis, not straight to this broker</h2>
 *
 * <p>{@code enableSimpleBroker} is an in-memory broker, and STOMP sessions live in this JVM's
 * memory. With N replicas a user's socket may be held by a different instance than the one whose
 * Kafka consumer received their event, and that instance's {@code convertAndSendToUser} would find
 * no session and drop the push - silently, and only for some users, some of the time. Phase 07
 * task 9's fix is {@code RedisFanOutRelay}: {@code NotificationFanOut} and
 * {@code RateTickBroadcaster} publish to a shared Redis channel instead of calling this broker
 * directly, every instance subscribes to it, and whichever instance actually holds the session
 * delivers the frame. That is what lets {@code notification-service} run more than one replica at
 * all - the one thing every other service in this project could already do.
 *
 * <p>The proper production answer is an external STOMP broker relay ({@code enableStompBrokerRelay}
 * against RabbitMQ or ActiveMQ) rather than Redis pub/sub; {@code docs/failure-modes.md} records why
 * this project takes the cheaper route instead of building that.
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
        // /queue for per-user pushes (NotificationFanOut); /topic for the one broadcast
        // destination this service has, RateTickBroadcaster's /topic/rates (task 9) - a rate is
        // nobody's secret, so it needs no per-user addressing at all.
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setUserDestinationPrefix(USER_DESTINATION_PREFIX);
        // No setApplicationDestinationPrefixes on purpose - there is no @MessageMapping here. The
        // socket is one-way by design: everything a client wants to *do* goes over HTTP through the
        // gateway, where it is authenticated, rate-limited and idempotent. A writable socket would
        // be a second, weaker door into the same money. Subscribing to /topic/rates needs no
        // app-level route either - the simple broker handles STOMP SUBSCRIBE natively.
    }
}
