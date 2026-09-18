package com.revolutclone.ledger.reservation.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** No {@code reservation} row with this id. */
public class ReservationNotFoundException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/reservation-not-found");

    public ReservationNotFoundException(UUID reservationId) {
        super(HttpStatus.NOT_FOUND, TYPE, "Reservation not found", "no reservation with id " + reservationId);
    }
}
