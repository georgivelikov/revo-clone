package com.revolutclone.ledger.web;

import com.revolutclone.ledger.domain.AccountBalanceRepository;
import java.util.List;
import java.util.UUID;

/** One reconciliation pass's result: every account whose cached total disagreed with {@code sum(postings)}. */
public record ReconcileResponse(List<DriftedAccount> drifted) {

    public static ReconcileResponse from(List<AccountBalanceRepository.DriftRow> rows) {
        return new ReconcileResponse(rows.stream()
                .map(row -> new DriftedAccount(row.getAccountId(), row.getComputedMinor(), row.getCachedMinor(),
                        row.getCurrency()))
                .toList());
    }

    public record DriftedAccount(UUID accountId, long computedMinor, long cachedMinor, String currency) {
    }
}
