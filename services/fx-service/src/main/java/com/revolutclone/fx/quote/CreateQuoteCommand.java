package com.revolutclone.fx.quote;

import java.util.UUID;

/**
 * plans/phase-04-fx-multicurrency.md's quote request - {@code { sellCurrency, buyCurrency, sellMinor
 * | buyMinor }} - plus the two things the client does not get to choose: who it is (the JWT subject)
 * and the idempotency key it presented.
 *
 * <p>Exactly one of {@code sellMinor}/{@code buyMinor} is set, which is what makes the Exchange
 * screen's "amount field on either side (bidirectional)" work. {@code null} rather than {@code 0}
 * distinguishes "not specified" from "specified as zero" - the latter is a rejection, not a default.
 */
public record CreateQuoteCommand(
        UUID userId,
        String idempotencyKey,
        String sellCurrency,
        String buyCurrency,
        Long sellMinor,
        Long buyMinor) {
}
