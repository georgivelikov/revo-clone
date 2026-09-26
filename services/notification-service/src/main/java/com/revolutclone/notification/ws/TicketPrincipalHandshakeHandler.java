package com.revolutclone.notification.ws;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * Turns the user id {@link TicketHandshakeInterceptor} put in the handshake attributes into the
 * session's {@link Principal}.
 *
 * <p>This is the half of the {@code /user/} destination machinery that is easy to miss.
 * {@code DefaultHandshakeHandler.determineUser} returns {@code request.getPrincipal()} by default -
 * the <em>HTTP</em> principal, which for this endpoint is anonymous, because the handshake carries
 * no {@code Authorization} header. Without this override every session would have a null user,
 * {@code convertAndSendToUser} would resolve to no session at all, and <strong>the symptom would be
 * silent non-delivery rather than an error</strong> - exactly what the phase's Risks &amp; gotchas
 * warns about for {@code /user/} destinations.
 */
public class TicketPrincipalHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        Object userId = attributes.get(TicketHandshakeInterceptor.USER_ID_ATTRIBUTE);
        if (userId == null) {
            // Unreachable while the interceptor is registered - it fails the handshake instead of
            // letting an unauthenticated upgrade through - but a null principal here would degrade
            // into undeliverable pushes rather than a refusal, so it is worth being loud about.
            throw new IllegalStateException("WebSocket handshake reached the upgrade with no redeemed ticket");
        }
        return new StompUserPrincipal(userId.toString());
    }
}
