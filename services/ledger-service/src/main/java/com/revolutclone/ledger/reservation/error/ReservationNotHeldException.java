package com.revolutclone.ledger.reservation.error;

import com.revolutclone.ledger.domain.ReservationState;
import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Capture or release was attempted on a reservation that is not {@code HELD} - it was already
 * captured, released or has expired. Reservations move through their state machine exactly
 * once per terminal transition; this is the guard against a second one.
 */
public class ReservationNotHeldException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/reservation-not-held");

    public ReservationNotHeldException(UUID reservationId, ReservationState actual) {
        super(HttpStatus.CONFLICT, TYPE, "Reservation not held",
                "reservation " + reservationId + " is " + actual + ", not HELD");
    }
}
