package com.revolutclone.fx.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The internal counterpart of {@link CreateQuoteRequest}, for a caller quoting on behalf of a
 * transfer to somebody else's wallet rather than its own - payment-service's cross-currency P2P
 * transfer (plans/phase-04-fx-multicurrency.md task 8). Identical to the public request except
 * for {@code recipientUserId}: the sell leg still reserves the caller's own wallet (the JWT
 * subject, forwarded from the paying user), but the buy leg is resolved against this user instead.
 */
public record InternalCreateQuoteRequest(
        @NotBlank @Size(min = 3, max = 3) String sellCurrency,
        @NotBlank @Size(min = 3, max = 3) String buyCurrency,
        @Positive Long sellMinor,
        @Positive Long buyMinor,
        @NotNull UUID recipientUserId) {
}
