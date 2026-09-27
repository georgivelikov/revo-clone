package com.revolutclone.card.service;

import com.revolutclone.card.domain.CardType;
import java.util.UUID;

/**
 * Everything {@link CardIssuanceService} needs to issue a card. {@code userId} comes from the
 * authenticated JWT subject, never the request body - a caller cannot issue a card for somebody
 * else. {@code pin} is the cardholder's own choice of step-up PIN for the reveal endpoint (see
 * V4__card_pin.sql); it is hashed before anything touches the database and is never persisted or
 * logged in clear.
 */
public record IssueCardCommand(
        UUID userId,
        UUID accountId,
        CardType type,
        String label,
        String pin) {
}
