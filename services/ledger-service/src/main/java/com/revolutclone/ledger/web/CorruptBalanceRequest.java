package com.revolutclone.ledger.web;

import jakarta.validation.constraints.NotNull;

/**
 * Body for the deliberate-drift admin endpoint (task 02.10). {@code deltaMinor} is added
 * directly to {@code available_minor}, bypassing {@code PostingEngine} entirely and every
 * balance-effect rule it enforces - this exists only so the reconciliation job's drift-detection
 * path can be exercised on demand, never as a real balance adjustment.
 */
public record CorruptBalanceRequest(@NotNull Long deltaMinor) {
}
