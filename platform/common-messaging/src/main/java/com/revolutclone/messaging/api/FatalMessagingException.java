package com.revolutclone.messaging.api;

/**
 * Explicit marker a consumer can throw to send a message straight to the dead-letter topic with
 * no retry - for a fatal condition the classifier has no other way to recognise (e.g. a
 * validation failure specific to one consumer's business rules).
 *
 * <p>A genuine business rejection (e.g. {@code INSUFFICIENT_FUNDS}) is <em>not</em> this - per
 * plans/phase-07-resilience-and-scale.md, that is a legitimate outcome that emits a rejection
 * event and commits the offset, never throws. This exception is for a message that really cannot
 * be processed at all.
 */
public class FatalMessagingException extends RuntimeException {

    public FatalMessagingException(String message) {
        super(message);
    }

    public FatalMessagingException(String message, Throwable cause) {
        super(message, cause);
    }
}
