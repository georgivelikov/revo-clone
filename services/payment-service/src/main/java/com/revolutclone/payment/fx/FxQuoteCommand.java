package com.revolutclone.payment.fx;

import java.util.UUID;

/**
 * Everything {@link FxQuotes#quote} needs: sell what the payer is spending, buy what the
 * recipient's account is denominated in, for the recipient's own wallet rather than the caller's.
 *
 * <p>{@code idempotencyKey} is namespaced by {@code initiatorUserId} by the caller
 * ({@code CrossCurrencyPaymentInitiator}), not passed through raw: {@code fx_quote.idempotency_key}
 * is globally unique, while the client's own {@code Idempotency-Key} header is unique only per
 * initiator, so two different users retrying with the same header value must not collide there.
 */
public record FxQuoteCommand(
        String idempotencyKey,
        String sellCurrency,
        String buyCurrency,
        long sellMinor,
        UUID recipientUserId) {
}
