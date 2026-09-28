package com.revolutclone.simulator.card;

import java.util.UUID;

/**
 * One spend, as asked for through the web layer. Every field but {@code cardId} is optional - a
 * caller (the curl in plans/phase-05-cards.md's Verification section, or a "spend" button with no
 * inputs of its own) can be as explicit or as hands-off as it likes; {@link CardSpendService} fills
 * whatever is left blank from {@link MerchantCatalogue}.
 */
record SpendCommand(UUID cardId, String merchantName, Long amountMinor, String currency) {
}
