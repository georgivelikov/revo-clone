package com.revolutclone.account.web;

import com.revolutclone.account.domain.Account;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code balance} is always {@code null} until Phase 02's ledger exists - see
 * plans/phase-01-identity-account-gateway.md's account-service design detail. Present in the
 * JSON explicitly rather than omitted, so clients can rely on the field always being there.
 */
public record AccountResponse(
        UUID id,
        UUID userId,
        String currency,
        String type,
        String name,
        String state,
        UUID ledgerAccountId,
        Long balance,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getUserId(),
                account.getCurrency(),
                account.getType().name(),
                account.getName(),
                account.getState().name(),
                account.getLedgerAccountId(),
                null,
                account.getCreatedAt());
    }
}
