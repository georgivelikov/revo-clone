package com.revolutclone.ledger.domain;

/** plans/phase-02-ledger.md's {@code journal_entry.type} column comment. */
public enum JournalEntryType {
    TRANSFER,
    FX,
    CARD_SETTLEMENT,
    FEE,
    ADJUSTMENT,
    REVERSAL
}
