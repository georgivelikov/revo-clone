package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/**
 * Published by {@code ledger-service} on {@code ledger.entry.v1}, keyed by {@code accountId}.
 * {@code amountMinor} is signed per the posting direction; {@code balanceAfterMinor} is the
 * resulting account balance, both in the smallest unit of {@code currency}.
 */
public record EntryPostedV1(
        UUID entryId,
        UUID accountId,
        String direction,
        long amountMinor,
        String currency,
        long balanceAfterMinor,
        String type,
        String referenceId) implements DomainEvent {
}
