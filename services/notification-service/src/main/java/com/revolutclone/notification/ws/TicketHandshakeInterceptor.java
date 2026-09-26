package com.revolutclone.notification.ws;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Authenticates the WebSocket handshake by redeeming the {@code ticket} query parameter, and puts
 * the resulting user id into the handshake attributes for {@link TicketPrincipalHandshakeHandler} to
 * turn into a {@link java.security.Principal}.
 *
 * <p>This runs <em>before</em> the upgrade: {@code WebSocketHttpRequestHandler} calls every
 * interceptor's {@link #beforeHandshake} and only then hands the request to the handshake handler,
 * so returning {@code false} here means no socket is ever opened. That is the whole point of the
 * ticket design - the connection is authenticated at the HTTP request that becomes it, not by a
 * frame sent afterwards over an already-open socket.
 *
 * <p>The security filter chain permits {@code /ws} anonymously (a browser cannot set an
 * {@code Authorization} header on a WebSocket handshake, which is the gotcha the whole ticket
 * mechanism exists for), so <strong>this interceptor is the only thing standing between the
 * internet and a user's event stream.</strong> It fails closed: no ticket, an unknown ticket, a
 * reused ticket and an expired ticket are one case, and that case is 401.
 */
@Component
public class TicketHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TicketHandshakeInterceptor.class);

    static final String TICKET_PARAM = "ticket";

    /** Read back by {@link TicketPrincipalHandshakeHandler#determineUser}. */
    static final String USER_ID_ATTRIBUTE = "revolut.userId";

    private final WebSocketTicketStore tickets;

    public TicketHandshakeInterceptor(WebSocketTicketStore tickets) {
        this.tickets = tickets;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams()
                .getFirst(TICKET_PARAM);
        Optional<String> userId = tickets.redeem(ticket);
        if (userId.isEmpty()) {
            // Never log the ticket itself, even a rejected one: a ticket that failed because it was
            // already used names a session that did succeed.
            log.warn("rejecting WebSocket handshake from {}: no valid ticket", request.getRemoteAddress());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(USER_ID_ATTRIBUTE, userId.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
            Exception exception) {
        // Nothing to undo: the ticket was consumed atomically, and a failed upgrade after a valid
        // ticket simply costs the client another ticket.
    }
}
