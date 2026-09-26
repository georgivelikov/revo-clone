package com.revolutclone.notification.ws;

import java.security.Principal;

/**
 * The {@link Principal} a redeemed ticket resolves to. {@link #getName()} is the Keycloak subject
 * (the user id), because that is the name {@code /user/{name}/queue/events} is addressed by: the
 * fan-out sends to {@code userId.toString()} and Spring's {@code UserDestinationMessageHandler}
 * matches it against this principal's name. Anything else here - an email, a username - would make
 * every push silently undeliverable.
 */
public record StompUserPrincipal(String userId) implements Principal {

    @Override
    public String getName() {
        return userId;
    }
}
