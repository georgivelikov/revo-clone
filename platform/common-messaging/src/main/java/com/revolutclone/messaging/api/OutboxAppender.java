package com.revolutclone.messaging.api;

import com.revolutclone.contracts.DomainEvent;

/**
 * Business code calls {@link #append} inside its own transaction and never touches a broker;
 * only {@code OutboxPublisher} (polling on a schedule) and the {@code spring/} adapter it
 * delegates to ever speak Kafka. This is what makes cross-service writes atomic with the
 * business change they describe.
 */
public interface OutboxAppender {

    /**
     * @param topic        the destination topic
     * @param partitionKey the entity id that orders this message - see CLAUDE.md hard rule 5
     * @param event        the event payload; wrapped in an {@code EventEnvelope} and persisted
     *                     as an unpublished outbox row in the same transaction as the caller's
     */
    void append(String topic, String partitionKey, DomainEvent event);
}
