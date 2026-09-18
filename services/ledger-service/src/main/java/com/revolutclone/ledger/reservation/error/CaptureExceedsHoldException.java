package com.revolutclone.ledger.reservation.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * A capture must be for at most the amount actually held - plans/phase-02-ledger.md: "Capture
 * may be for less than the hold; the difference is released." Never more.
 */
public class CaptureExceedsHoldException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/capture-exceeds-hold");

    public CaptureExceedsHoldException(UUID reservationId, long requestedMinor, long heldMinor) {
        super(HttpStatus.CONFLICT, TYPE, "Capture exceeds hold",
                "reservation " + reservationId + " holds " + heldMinor + " minor units, capture requested "
                        + requestedMinor);
    }
}
