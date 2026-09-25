package com.revolutclone.payment.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The body of {@code POST /api/payments}. Note what is <em>not</em> here: no initiator and no
 * idempotency key. The initiator is the authenticated JWT subject and the key is the
 * {@code Idempotency-Key} header, so neither is something a caller can choose for somebody
 * else.
 *
 * <p>{@code currency} is required even though it is derivable from the source account, so that
 * a client that thinks it is sending EUR and an account that is actually GBP produce a
 * {@code CURRENCY_MISMATCH} rejection rather than a silently reinterpreted amount. Amounts are
 * minor units, always - CLAUDE.md hard rule 1.
 */
public record CreatePaymentRequest(

        @NotNull(message = "sourceAccountId is required")
        UUID sourceAccountId,

        @NotNull(message = "targetAccountId is required")
        UUID targetAccountId,

        @Positive(message = "amountMinor must be a positive number of minor units")
        long amountMinor,

        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an uppercase ISO-4217 code")
        String currency,

        @Size(max = 140, message = "reference must be at most 140 characters")
        String reference) {
}
