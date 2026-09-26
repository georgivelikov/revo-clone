package com.revolutclone.notification.ws;

import java.time.Duration;

/**
 * A freshly minted ticket and how long the client has to use it. The TTL travels with the ticket so
 * the browser can decide whether to reuse an unopened one or ask for another, rather than hardcoding
 * a number the server owns.
 */
public record IssuedTicket(String ticket, Duration ttl) {
}
