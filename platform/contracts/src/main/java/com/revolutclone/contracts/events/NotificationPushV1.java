package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/**
 * A ready-to-deliver message for one user, published on {@code notification.push.v1} keyed by
 * {@code userId}. Unlike every other event notification-service consumes, this one carries no
 * domain facts to interpret: the producer has already decided what the user should be told.
 *
 * <p>The producer in this project is {@code analytics-service}'s budget alerting (phase 06) -
 * see plans/phase-06-vaults-analytics.md, which emits here on crossing 80% or 100% of a budget
 * and guards on its own {@code alerted_at_*} columns so a projection replay does not re-notify.
 * The topic exists from phase 03 because notification-service's fan-out consumes it from the
 * start (plans/phase-03-payments-and-ui.md, {@code ### notification-service}); any later service
 * with something to say to a user publishes here rather than growing its own push path.
 *
 * <p>{@code category} is free text for the UI to group or icon on (e.g. {@code BUDGET}), not an
 * enum: a consumer that threw on a category its upstream invented later would stall the whole
 * topic over a display detail.
 */
public record NotificationPushV1(
        UUID userId,
        String category,
        String title,
        String body) implements DomainEvent {
}
