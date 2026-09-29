package com.revolutclone.analytics.web;

import com.revolutclone.analytics.vault.Vault;
import java.time.Instant;
import java.util.UUID;

public record VaultResponse(
        UUID id,
        UUID userId,
        UUID accountId,
        String name,
        String currency,
        Long goalMinor,
        boolean roundupEnabled,
        UUID roundupSourceCardId,
        Instant createdAt) {

    public static VaultResponse from(Vault vault) {
        return new VaultResponse(
                vault.getId(),
                vault.getUserId(),
                vault.getAccountId(),
                vault.getName(),
                vault.getCurrency(),
                vault.getGoalMinor(),
                vault.isRoundupEnabled(),
                vault.getRoundupSourceCardId(),
                vault.getCreatedAt());
    }
}
