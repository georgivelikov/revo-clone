package com.revolutclone.contracts;

/**
 * Marker interface for every event payload. Each event is a {@code record} implementing this
 * interface, versioned by class name suffix ({@code PaymentInitiatedV1}).
 *
 * <p>Evolution rule: <b>add optional fields only</b>. A breaking change means a new
 * {@code V2} record and a new topic suffix, with both consumed during migration - never
 * change or remove a field on an existing event.
 */
public interface DomainEvent {
}
