package com.revolutclone.ledger.web;

import com.revolutclone.ledger.domain.JournalEntryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record CaptureRequest(
        @NotNull UUID counterpartyAccountId,
        @Positive long amountMinor,
        @NotNull JournalEntryType type,
        @NotBlank String idempotencyKey,
        UUID referenceId) {
}
