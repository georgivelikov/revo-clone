package com.revolutclone.ledger.web;

import com.revolutclone.ledger.domain.AccountBalance;
import java.util.UUID;

public record BalanceResponse(
        UUID ledgerAccountId,
        long availableMinor,
        long reservedMinor,
        String currency) {

    static BalanceResponse from(AccountBalance balance) {
        return new BalanceResponse(balance.getLedgerAccountId(), balance.getAvailableMinor(),
                balance.getReservedMinor(), balance.getCurrency());
    }
}
