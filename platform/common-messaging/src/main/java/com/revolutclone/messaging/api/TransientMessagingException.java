package com.revolutclone.messaging.api;

/**
 * Explicit marker a consumer can throw to force the retry-topic chain regardless of what the
 * default classifier would have decided - for a transient condition the classifier has no other
 * way to recognise (e.g. a domain-specific downstream dependency).
 */
public class TransientMessagingException extends RuntimeException {

    public TransientMessagingException(String message) {
        super(message);
    }

    public TransientMessagingException(String message, Throwable cause) {
        super(message, cause);
    }
}
