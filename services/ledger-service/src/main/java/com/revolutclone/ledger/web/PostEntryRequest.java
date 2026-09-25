package com.revolutclone.ledger.web;

import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.posting.PostEntryCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * {@code idempotencyKey} here is the SERVER-DERIVED key the calling service already computed
 * (e.g. {@code payment:<paymentId>}) - never a raw client {@code Idempotency-Key} HTTP header.
 * This endpoint is service-to-service ({@code /internal/**}); the header-to-key derivation, if
 * any, is the caller's own concern before it reaches here. See {@link PostEntryCommand}'s
 * javadoc for why forwarding a client header directly would be a real bug.
 */
public record PostEntryRequest(
        @NotBlank String idempotencyKey,
        @NotNull JournalEntryType type,
        UUID referenceId,
        @NotNull @Size(min = 2) List<@Valid PostingLineRequest> postings) {

    PostEntryCommand toCommand() {
        return new PostEntryCommand(idempotencyKey, type, referenceId,
                postings.stream().map(PostingLineRequest::toPostingLine).toList());
    }
}
