package com.revolutclone.payment.web;

import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.service.InitiatePaymentCommand;
import com.revolutclone.payment.service.PaymentInitiator;
import com.revolutclone.payment.web.error.MissingIdempotencyKeyException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A second entry point onto {@link PaymentInitiator#initiate}, for a caller with no live user JWT
 * at all - a Kafka consumer, or another backend service acting on a user's behalf, rather than a
 * browser request. {@link PaymentInitiator#initiate} already took an explicit
 * {@link InitiatePaymentCommand#initiatorUserId()} rather than reading a principal, so this
 * controller is the only new code the capability needed - {@link PaymentController} and this
 * class both end at the same method, differing only in where {@code initiatorUserId} comes from.
 *
 * <p><strong>Built generally, not vault-specific.</strong> analytics-service's vault
 * move-in/move-out (plans/phase-06-vaults-analytics.md task 6) is the first caller; task 06.7's
 * round-up rule is the second, initiating a transfer from a Kafka-consumer thread with no request
 * at all to carry a JWT on. Neither caller is special-cased here - both send the same shape.
 *
 * <p><strong>Still requires {@code Idempotency-Key}.</strong> CLAUDE.md hard rule 3 makes no
 * exception for an internal caller: every money-moving POST takes one, and honours it.
 *
 * <p><strong>Deliberately not behind a JWT, unlike {@link PaymentController}.</strong> Same
 * precedent as account-service's {@code InternalVaultAccountController} and card-service's
 * {@code NetworkPanController}: the caller is another backend service, not a live user, and this
 * project has no service-to-service client-credentials identity yet (a Phase 07 concern). Not
 * routed by {@code api-gateway} - service-to-service only, over the internal Docker network.
 */
@RestController
@RequestMapping("/internal/payments")
public class InternalPaymentController {

    private final PaymentInitiator initiator;

    public InternalPaymentController(PaymentInitiator initiator) {
        this.initiator = initiator;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> initiate(
            @RequestHeader(value = PaymentController.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody InternalCreatePaymentRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }

        Payment payment = initiator.initiate(new InitiatePaymentCommand(
                request.userId(),
                idempotencyKey.strip(),
                request.sourceAccountId(),
                request.targetAccountId(),
                request.amountMinor(),
                request.currency(),
                request.reference()));

        return ResponseEntity.accepted().body(PaymentResponse.from(payment));
    }
}
