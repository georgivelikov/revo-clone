package com.revolutclone.ledger.web;

import com.revolutclone.ledger.domain.Reservation;
import com.revolutclone.ledger.domain.ReservationState;
import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
        UUID id,
        UUID ledgerAccountId,
        long amountMinor,
        String currency,
        ReservationState state,
        UUID referenceId,
        Instant expiresAt) {

    static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(reservation.getId(), reservation.getLedgerAccountId(),
                reservation.getAmountMinor(), reservation.getCurrency(), reservation.getState(),
                reservation.getReferenceId(), reservation.getExpiresAt());
    }
}
