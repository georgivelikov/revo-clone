package com.revolutclone.payment.web;

import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.service.InitiatePaymentCommand;
import com.revolutclone.payment.service.PaymentInitiator;
import com.revolutclone.payment.service.PaymentQueryService;
import com.revolutclone.payment.web.error.MissingIdempotencyKeyException;
import com.revolutclone.security.api.CurrentUser;
import com.revolutclone.security.api.UserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final PaymentInitiator initiator;
    private final PaymentQueryService queries;

    public PaymentController(PaymentInitiator initiator, PaymentQueryService queries) {
        this.initiator = initiator;
        this.queries = queries;
    }

    /**
     * <strong>202, not 201.</strong> The transfer has been accepted, not completed - the money
     * has not moved and will not until ledger-service consumes {@code PaymentInitiatedV1} and
     * posts the entry. plans/phase-03-payments-and-ui.md is explicit that this is the honest
     * contract for an asynchronous system, and that returning 201 here would let the UI pretend
     * the write was synchronous. The returned status is always {@code PENDING} for a new
     * payment; a duplicate key returns whatever the original payment has since become, which
     * may already be {@code COMPLETED} or {@code FAILED}.
     *
     * <p>No {@code Location} header for the same reason: there is no created resource whose
     * final state a client can rely on yet, only an accepted instruction. The client polls
     * {@code paymentId} or - preferably - waits for the WebSocket event.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> initiate(
            @CurrentUser UserPrincipal principal,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }

        Payment payment = initiator.initiate(new InitiatePaymentCommand(
                UUID.fromString(principal.userId()),
                idempotencyKey.strip(),
                request.sourceAccountId(),
                request.targetAccountId(),
                request.amountMinor(),
                request.currency(),
                request.reference()));

        return ResponseEntity.accepted().body(PaymentResponse.from(payment));
    }

    /**
     * The activity feed (task 09.6): every payment that moved money into or out of one of the
     * caller's own accounts, newest first. See {@link PaymentQueryService#feedFor}.
     */
    @GetMapping
    public List<PaymentResponse> feed(@CurrentUser UserPrincipal principal) {
        return queries.feedFor(UUID.fromString(principal.userId())).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    /**
     * The poll fallback from plans/phase-03-payments-and-ui.md's realtime section (task 03.10):
     * "if no event arrives within ~5s, fall back to polling GET /api/payments/{id}".
     */
    @GetMapping("/{paymentId}")
    public PaymentResponse get(@CurrentUser UserPrincipal principal, @PathVariable UUID paymentId) {
        return PaymentResponse.from(queries.getVisibleTo(UUID.fromString(principal.userId()), paymentId));
    }
}
