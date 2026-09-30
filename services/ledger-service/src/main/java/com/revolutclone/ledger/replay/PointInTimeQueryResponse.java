package com.revolutclone.ledger.replay;

import com.revolutclone.ledger.domain.PointInTimeSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code GET /internal/ledger/admin/point-in-time}'s response body. */
public record PointInTimeQueryResponse(Instant asOf, Instant generatedAt, List<AccountBalance> balances) {

    public static PointInTimeQueryResponse from(Instant asOf, Instant generatedAt, List<PointInTimeSnapshot> rows) {
        return new PointInTimeQueryResponse(asOf, generatedAt, rows.stream()
                .map(row -> new AccountBalance(row.getLedgerAccountId(), row.getCurrency(),
                        row.getBalanceAfterMinor(), row.getEntryId(), row.getOccurredAt()))
                .toList());
    }

    public record AccountBalance(UUID ledgerAccountId, String currency, long balanceAfterMinor, UUID entryId,
            Instant occurredAt) {
    }
}
