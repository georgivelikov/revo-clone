package com.revolutclone.payment.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The body of {@code POST /internal/payments} - see {@link InternalPaymentController}. Unlike
 * {@link CreatePaymentRequest}, {@code userId} is an explicit field rather than the JWT subject:
 * this endpoint is called from a context with no live user JWT at all, so the caller has to say
 * who the transfer is on behalf of. Everything else is identical to the public request shape -
 * this is the same transfer, initiated a different way.
 */
public record InternalCreatePaymentRequest(

        @NotNull(message = "userId is required")
        UUID userId,

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
