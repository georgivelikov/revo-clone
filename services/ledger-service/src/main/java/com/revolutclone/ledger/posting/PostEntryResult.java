package com.revolutclone.ledger.posting;

import com.revolutclone.ledger.domain.JournalEntry;
import com.revolutclone.ledger.domain.Posting;
import java.util.List;
import java.util.UUID;

/**
 * Either the entry now exists (freshly posted, or the exact same entry an earlier call with
 * the same {@code idempotencyKey} already created - {@link IdempotentEntryTest} asserts both
 * paths return the same {@link Posted} shape), or it was rejected before anything was written.
 * A rejection is a normal, expected business outcome with a named reason - not an exception.
 */
public sealed interface PostEntryResult {

    record Posted(JournalEntry entry, List<Posting> postings) implements PostEntryResult {
    }

    record Rejected(EntryRejectionReason reason, UUID accountId, String idempotencyKey) implements PostEntryResult {
    }
}
