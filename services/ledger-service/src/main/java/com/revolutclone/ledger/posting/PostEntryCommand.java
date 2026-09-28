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
 *
 * <p>{@code overdraftPolicy} is the never-negative rule stated per entry rather than per account
 * type - see {@link OverdraftPolicy} for why it has to be, and task 05.6's over-settlement leg for
 * the one caller that asks for anything other than the default.
 */
public record PostEntryCommand(String idempotencyKey, JournalEntryType type, UUID referenceId,
        List<PostingLine> postings, OverdraftPolicy overdraftPolicy) {

    public PostEntryCommand {
        if (overdraftPolicy == null) {
            throw new IllegalArgumentException("overdraftPolicy must not be null");
        }
    }

    /**
     * The ordinary entry: a customer account may not be taken negative. Every caller that has no
     * opinion about overdraw gets this, which is the point of it being a separate constructor -
     * relaxing the rule has to be written down at the call site to happen at all.
     */
    public PostEntryCommand(String idempotencyKey, JournalEntryType type, UUID referenceId,
            List<PostingLine> postings) {
        this(idempotencyKey, type, referenceId, postings, OverdraftPolicy.FORBID);
    }
}
