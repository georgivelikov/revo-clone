package com.revolutclone.messaging.api;

import com.revolutclone.contracts.DomainEvent;
import com.revolutclone.contracts.EventEnvelope;

/**
 * Implemented by consumers. The {@code @KafkaListener} annotation lives in the thin
 * {@code spring/} adapter that delegates here, which is what makes every handler
 * unit-testable with no broker at all.
 */
public interface EventHandler<T extends DomainEvent> {

    void handle(EventEnvelope<T> envelope);
}
