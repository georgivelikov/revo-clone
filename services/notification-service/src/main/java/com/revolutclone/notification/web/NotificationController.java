package com.revolutclone.notification.web;

import com.revolutclone.notification.config.NotificationProperties;
import com.revolutclone.notification.domain.Notification;
import com.revolutclone.notification.domain.NotificationRepository;
import com.revolutclone.notification.ws.IssuedTicket;
import com.revolutclone.notification.ws.WebSocketTicketStore;
import com.revolutclone.security.api.CurrentUser;
import com.revolutclone.security.api.UserPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Everything this service exposes over HTTP: the ticket that buys a WebSocket, and the persisted feed
 * behind the socket.
 *
 * <p>All three routes require a valid JWT - the ticket endpoint especially, since it is the point at
 * which a bearer token is exchanged for something a WebSocket URL can carry. Only {@code /ws} itself
 * is anonymous; see {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final WebSocketTicketStore tickets;
    private final NotificationRepository notifications;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationController(WebSocketTicketStore tickets, NotificationRepository notifications,
            NotificationProperties properties, Clock clock) {
        this.tickets = tickets;
        this.notifications = notifications;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Mints a single-use, short-lived ticket for the caller's own user id.
     *
     * <p>A POST, not a GET, because it has a side effect that matters: it writes a credential to
     * Redis. That also keeps it off anything that caches or logs GET URLs, which is the entire point
     * of the mechanism - see {@link WebSocketTicketStore} for why the alternative (a JWT in the
     * WebSocket query string) was rejected.
     */
    @PostMapping("/ticket")
    public TicketResponse issueTicket(@CurrentUser UserPrincipal principal) {
        IssuedTicket issued = tickets.issue(principal.userId());
        return TicketResponse.from(issued);
    }

    /**
     * The feed and the unread badge in one response, newest first. Page size is configuration, not a
     * query parameter: the UI shows a fixed-height history and a count, and letting a client ask for
     * a million rows is a denial-of-service surface with no product behind it.
     */
    @GetMapping
    public NotificationFeedResponse feed(@CurrentUser UserPrincipal principal) {
        UUID userId = UUID.fromString(principal.userId());
        List<Notification> page = notifications.findByUserIdOrderByCreatedAtDesc(userId,
                PageRequest.of(0, properties.feed().pageSize()));
        long unread = notifications.countByUserIdAndReadAtIsNull(userId);
        return NotificationFeedResponse.of(page, unread);
    }

    /**
     * Marks one notification read. Scoped by user in the query itself, so another user's id is a 404
     * rather than a successful write - and 404, not 403, because "exists but is not yours" is not
     * something this endpoint should confirm.
     *
     * <p>{@code readAt} is wall-clock here, unlike everywhere else in this service: it records when
     * <em>this user</em> looked at the thing, which is not an event time and has no event to take one
     * from.
     */
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@CurrentUser UserPrincipal principal, @PathVariable UUID id) {
        Notification notification = notifications.findByIdAndUserId(id, UUID.fromString(principal.userId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such notification"));
        notification.markRead(clock.instant());
        notifications.save(notification);
        return ResponseEntity.noContent().build();
    }
}
