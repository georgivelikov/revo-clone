package com.revolutclone.messaging.api;

/**
 * The outcome of classifying a consumer's thrown exception, per
 * plans/phase-07-resilience-and-scale.md §Retry topics and the dead-letter queue. This is the
 * design decision the phase calls out explicitly - the plumbing that acts on it is secondary.
 */
public enum RetryClassification {

    /** DB connection lost, optimistic lock exhausted, downstream 503 - worth another attempt. */
    TRANSIENT,

    /**
     * Deserialization failure, unknown event type, validation failure, business rejection - will
     * never succeed no matter how many times it is retried, so retrying it only delays the DLT
     * and, for a poison message, stalls the partition behind it.
     */
    FATAL
}
