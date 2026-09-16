package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/**
 * Published by {@code ledger-service} after consuming {@link AccountOpenedV1}, so
 * {@code account-service} can fill in its own {@code ledger_account_id}.
 */
public record LedgerAccountCreatedV1(
        UUID accountId,
        UUID ledgerAccountId) implements DomainEvent {
}
