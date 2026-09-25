package com.revolutclone.ledger.web;

import com.revolutclone.ledger.domain.JournalEntry;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.Posting;
import java.util.List;
import java.util.UUID;

public record PostEntryResponse(
        UUID entryId,
        String idempotencyKey,
        JournalEntryType type,
        UUID referenceId,
        List<PostingResponse> postings) {

    static PostEntryResponse from(JournalEntry entry, List<Posting> postings) {
        return new PostEntryResponse(entry.getId(), entry.getIdempotencyKey(), entry.getType(),
                entry.getReferenceId(), postings.stream().map(PostingResponse::from).toList());
    }
}
