package com.revolutclone.ledger.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * What card-service says when a merchant submits a settlement: how much was captured and how much of
 * it is the issuer's own mark-up rather than the merchant's money.
 *
 * <p>Deliberately does <em>not</em> carry the wallet account, the counterparty accounts, the hold
 * amount or the entry type. Every one of those is the ledger's own fact and is read or resolved
 * there - see {@code CardSettlementService}. What the caller knows, and this carries, is what the
 * network told it.
 *
 * <p>{@code networkRef} rather than a free-form idempotency key: the settlement's key has to be
 * derived from the network's reference and prefixed {@code cardauth:} to be accepted at all, so the
 * derivation belongs on the side that enforces the rule.
 */
public record CardSettlementRequest(
        @Positive long settledMinor,
        @PositiveOrZero long feeMinor,
        @NotBlank String networkRef) {
}
