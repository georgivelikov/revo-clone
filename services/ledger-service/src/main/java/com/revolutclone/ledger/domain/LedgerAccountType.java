package com.revolutclone.ledger.domain;

/**
 * plans/phase-02-ledger.md's {@code ledger_account.type} column comment. {@code USER_WALLET}
 * and {@code VAULT} must never go negative on {@code available_minor}; every other type is a
 * system account and is allowed to, since that is what makes it the counterparty in a
 * balanced entry.
 */
public enum LedgerAccountType {
    USER_WALLET,
    VAULT,
    FX_POOL,
    CARD_SETTLEMENT,
    FEE_INCOME,
    FX_PNL,
    EXTERNAL_CLEARING;

    public boolean isSystemAccount() {
        return this != USER_WALLET && this != VAULT;
    }
}
