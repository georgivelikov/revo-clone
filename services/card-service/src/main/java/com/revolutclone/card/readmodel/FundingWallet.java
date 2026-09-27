package com.revolutclone.card.readmodel;

import java.util.UUID;

/**
 * Everything the authorization chain needs to know about the wallet behind a card: which ledger
 * account a hold is placed on, and the currency the hold has to be denominated in.
 */
public record FundingWallet(UUID accountId, UUID ledgerAccountId, String currency) {
}
