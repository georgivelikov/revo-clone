package com.revolutclone.card.authz;

import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.DeclineReason;
import java.util.UUID;

/**
 * What the chain decided, for the caller's log line. The authoritative record is the
 * {@code card_authorization} row and the event beside it; this is the return value the consumer uses
 * to say what happened without re-reading either.
 *
 * <p>{@code authorizationId} is null for {@code CARD_NOT_FOUND} - a PAN that resolves to no card has
 * no row to point at.
 */
public record AuthorizationDecision(
        UUID authorizationId,
        AuthorizationState state,
        DeclineReason declineReason,
        UUID reservationId) {

    public static AuthorizationDecision approved(UUID authorizationId, UUID reservationId) {
        return new AuthorizationDecision(authorizationId, AuthorizationState.APPROVED, null, reservationId);
    }

    public static AuthorizationDecision declined(UUID authorizationId, DeclineReason reason) {
        return new AuthorizationDecision(authorizationId, AuthorizationState.DECLINED, reason, null);
    }

    /** The answer a resent {@code networkRef} gets: the decision already made, unchanged. */
    public static AuthorizationDecision of(CardAuthorization authorization) {
        return new AuthorizationDecision(authorization.getId(), authorization.getState(),
                authorization.getDeclineReason(), authorization.getReservationId());
    }

    public boolean isApproved() {
        return state == AuthorizationState.APPROVED;
    }
}
