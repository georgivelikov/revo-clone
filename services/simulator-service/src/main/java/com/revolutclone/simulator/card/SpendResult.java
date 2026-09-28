package com.revolutclone.simulator.card;

import java.util.UUID;

/** What a caller gets back from one spend - enough to reverse it later by {@code networkRef}. */
public record SpendResult(String networkRef, UUID cardId, String merchantName, String merchantMcc,
        String merchantCountry, long amountMinor, String currency, int settleOnDay) {
}
