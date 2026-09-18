package com.revolutclone.ledger.reservation;

import com.revolutclone.ledger.domain.JournalEntry;
import com.revolutclone.ledger.domain.Posting;
import java.util.List;

/**
 * {@code capturedMinor} is what was actually posted; {@code releasedRemainderMinor} is what was
 * returned to {@code available} because the capture was for less than the full hold (zero on a
 * full capture). Both are always present so a caller never has to re-derive the split.
 */
public record CaptureResult(JournalEntry entry, List<Posting> postings, long capturedMinor,
        long releasedRemainderMinor) {
}
