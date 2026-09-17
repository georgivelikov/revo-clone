package com.revolutclone.ledger.posting;

import com.revolutclone.ledger.domain.JournalEntryType;
import java.util.List;
import java.util.UUID;

/**
 * {@code idempotencyKey} must already be server-derived by the caller
 * ({@code payment:<paymentId>}, {@code fxquote:<quoteId>}, {@code cardauth:<networkRef>}, or a
 * free-form admin/system key for {@code ADJUSTMENT}/{@code FEE}/{@code REVERSAL} entries) -
 * {@link PostingEngine} validates the prefix but never constructs the key itself, and never
 * accepts a client's raw {@code Idempotency-Key} header directly (see
 * plans/phase-02-ledger.md's warning on this in the {@code journal_entry} schema notes).
 */
public record PostEntryCommand(String idempotencyKey, JournalEntryType type, UUID referenceId,
        List<PostingLine> postings) {
}
