package com.revolutclone.ledger.web;

import com.revolutclone.ledger.domain.Reservation;
import com.revolutclone.ledger.reservation.CaptureResult;
import com.revolutclone.ledger.reservation.CardSettlementResult;
import com.revolutclone.ledger.reservation.CardSettlementService;
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
    private final CardSettlementService cardSettlementService;

    public ReservationController(ReservationService reservationService,
            CardSettlementService cardSettlementService) {
        this.reservationService = reservationService;
        this.cardSettlementService = cardSettlementService;
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

    /**
     * plans/phase-05-cards.md's settlement cases, as one call: capture the hold (in full, or the
     * lesser amount with the difference released) and post any excess directly. Task 05.6.
     *
     * <p>A route of its own rather than {@code /capture} with more fields, because it is a different
     * operation: {@code /capture} captures at most what is held, and this one deliberately may not -
     * an over-settlement posts beyond the hold and takes the wallet negative if it must. Keeping
     * them apart means nothing that calls {@code /capture} can reach the overdraw path by accident.
     */
    @PostMapping("/{id}/card-settlement")
    public ResponseEntity<CardSettlementResponse> settleCardAuthorization(@PathVariable UUID id,
            @Valid @RequestBody CardSettlementRequest request) {
        CardSettlementResult result;
        try {
            result = cardSettlementService.settle(id, request.settledMinor(), request.feeMinor(),
                    request.networkRef());
        } catch (IllegalArgumentException ex) {
            throw new InvalidLedgerRequestException(ex.getMessage());
        }
        return ResponseEntity.ok(CardSettlementResponse.from(result));
    }

    @PostMapping("/{id}/release")
    public ResponseEntity<Void> release(@PathVariable UUID id) {
        reservationService.release(id);
        return ResponseEntity.ok().build();
    }

    /**
     * The hold's owner has concluded it will never be claimed - plans/phase-05-cards.md's
     * "Expiry (7 days, no settlement): sweep releases the hold", asked for by name instead of waited
     * for. Task 05.7.
     *
     * <p>Separate from {@code /release} because the two differ in what an already-concluded
     * reservation means. A release is a decision someone made, so finding the row already
     * {@code EXPIRED} means the decision arrived too late and is refused; an expiry is a deadline
     * that has passed, so finding it already expired is the same outcome by another route and is
     * answered 200. The owner's deadline and the hold's own {@code expires_at} are the same instant,
     * so that is the common case, not a rare one.
     */
    @PostMapping("/{id}/expire")
    public ResponseEntity<Void> expire(@PathVariable UUID id) {
        reservationService.expire(id);
        return ResponseEntity.ok().build();
    }
}
