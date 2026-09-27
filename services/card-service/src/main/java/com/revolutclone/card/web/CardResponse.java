package com.revolutclone.card.web;

import com.revolutclone.card.domain.Card;
import java.time.Instant;
import java.util.UUID;

/**
 * What the issuance (and, later, query) endpoints return. Deliberately never carries the PAN or
 * CVV in any form - {@code panLast4} is the only PAN-derived field, and the full number is only
 * ever available from the one step-up-gated reveal endpoint.
 */
public record CardResponse(
        UUID id,
        UUID userId,
        UUID accountId,
        String panLast4,
        int expiryMonth,
        int expiryYear,
        String type,
        String state,
        String label,
        Instant createdAt) {

    public static CardResponse from(Card card) {
        return new CardResponse(
                card.getId(),
                card.getUserId(),
                card.getAccountId(),
                card.getPanLast4(),
                card.getExpiryMonth(),
                card.getExpiryYear(),
                card.getType().name(),
                card.getState().name(),
                card.getLabel(),
                card.getCreatedAt());
    }
}
