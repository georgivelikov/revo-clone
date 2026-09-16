package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/**
 * Published by {@code ledger-service}'s reconciliation job when a wallet's computed balance
 * disagrees with its stored balance.
 */
public record LedgerDriftDetectedV1(
        UUID accountId,
        long expectedBalanceMinor,
        long actualBalanceMinor,
        String currency) implements DomainEvent {
}
