package com.revolutclone.notification.web;

import com.revolutclone.notification.ws.IssuedTicket;

/**
 * What {@code POST /api/notifications/ticket} returns. The TTL travels with the ticket so the client
 * does not hardcode a lifetime the server owns.
 *
 * @param ticket           the single-use handshake ticket, to be passed as {@code ?ticket=} on the
 *                         WebSocket URL
 * @param expiresInSeconds how long the client has to open the socket with it
 */
public record TicketResponse(String ticket, long expiresInSeconds) {

    public static TicketResponse from(IssuedTicket issued) {
        return new TicketResponse(issued.ticket(), issued.ttl().toSeconds());
    }
}
