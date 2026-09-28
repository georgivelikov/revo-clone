package com.revolutclone.card.web;

import com.revolutclone.card.domain.CardLimit;

/** What the limits endpoints return - one ceiling, in the currency it is actually enforced in. */
public record CardLimitResponse(String period, long amountMinor, String currency) {

    public static CardLimitResponse from(CardLimit limit) {
        return new CardLimitResponse(limit.getPeriod().name(), limit.getAmountMinor(), limit.getCurrency());
    }
}
