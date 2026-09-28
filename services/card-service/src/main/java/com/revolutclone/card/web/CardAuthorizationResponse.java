package com.revolutclone.card.web;

import com.revolutclone.card.domain.CardAuthorization;
import java.time.Instant;

/**
 * One row of the per-card spend feed. Carries both {@code amountMinor}/{@code currency} (the
 * merchant's side) and {@code billingMinor}/{@code billingCurrency} (the wallet's) so the UI can
 * show "you spent $50, we charged you €46.12" without a second call - same pair
 * {@code AuthorizationApprovedV1} carries, for the same reason.
 */
public record CardAuthorizationResponse(
        String networkRef,
        String merchantName,
        String merchantMcc,
        String merchantCountry,
        long amountMinor,
        String currency,
        Long billingMinor,
        String billingCurrency,
        String state,
        String declineReason,
        Long settledMinor,
        Long excessMinor,
        Instant authorizedAt,
        Instant settledAt) {

    public static CardAuthorizationResponse from(CardAuthorization authorization) {
        return new CardAuthorizationResponse(
                authorization.getNetworkRef(),
                authorization.getMerchantName(),
                authorization.getMerchantMcc(),
                authorization.getMerchantCountry(),
                authorization.getAmountMinor(),
                authorization.getCurrency(),
                authorization.getBillingMinor(),
                authorization.getBillingCurrency(),
                authorization.getState().name(),
                authorization.getDeclineReason() == null ? null : authorization.getDeclineReason().name(),
                authorization.getSettledMinor(),
                authorization.getExcessMinor(),
                authorization.getAuthorizedAt(),
                authorization.getSettledAt());
    }
}
