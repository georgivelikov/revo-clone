package com.revolutclone.ledger.web;

import com.revolutclone.ledger.domain.Reservation;
import com.revolutclone.ledger.reservation.CaptureResult;
import com.revolutclone.ledger.reservation.ReservationService;
import com.revolutclone.ledger.web.error.InvalidLedgerRequestException;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * plans/phase-02-ledger.md's {@code ### API}: hold / capture / release. Business rejections
 * ({@code InsufficientAvailableBalanceException}, {@code ReservationNotFoundException},
 * {@code ReservationNotHeldException}, {@code CaptureExceedsHoldException}) already extend
 * {@code ApiException} and are mapped by {@code common-web}'s {@code ApiExceptionHandler}
 * without this controller needing to know about them individually - only genuinely malformed
 * input ({@code IllegalArgumentException} from the service layer's own guard clauses) needs
 * translation here.
 */
@RestController
@RequestMapping("/internal/ledger/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> hold(@Valid @RequestBody HoldRequest request) {
        Reservation reservation;
        try {
            reservation = reservationService.hold(request.ledgerAccountId(), request.amountMinor(),
                    request.currency(), request.referenceId(), request.idempotencyKey(), request.expiresAt());
        } catch (IllegalArgumentException ex) {
            throw new InvalidLedgerRequestException(ex.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ReservationResponse.from(reservation));
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<CaptureResponse> capture(@PathVariable UUID id, @Valid @RequestBody CaptureRequest request) {
        CaptureResult result;
        try {
            result = reservationService.capture(id, request.counterpartyAccountId(), request.amountMinor(),
                    request.type(), request.idempotencyKey(), request.referenceId());
        } catch (IllegalArgumentException ex) {
            throw new InvalidLedgerRequestException(ex.getMessage());
        }
        return ResponseEntity.ok(CaptureResponse.from(result));
    }

    @PostMapping("/{id}/release")
    public ResponseEntity<Void> release(@PathVariable UUID id) {
        reservationService.release(id);
        return ResponseEntity.ok().build();
    }
}
