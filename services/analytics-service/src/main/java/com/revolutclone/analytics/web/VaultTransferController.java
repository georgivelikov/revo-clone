package com.revolutclone.analytics.web;

import com.revolutclone.analytics.payment.InitiatedPayment;
import com.revolutclone.analytics.vault.VaultTransferService;
import com.revolutclone.analytics.web.error.MissingIdempotencyKeyException;
import com.revolutclone.security.api.CurrentUser;
import com.revolutclone.security.api.UserPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Move-in/move-out (task 06.6) - see {@link VaultTransferService}. {@code 202}, not {@code 200}
 * or {@code 201}: the transfer has been accepted by payment-service, not completed, exactly as
 * {@code POST /api/payments} itself answers - the money has not moved until ledger-service
 * consumes the resulting event.
 */
@RestController
@RequestMapping("/api/analytics/vaults")
public class VaultTransferController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final VaultTransferService transferService;

    public VaultTransferController(VaultTransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/{id}/transfer")
    public ResponseEntity<VaultTransferResponse> transfer(@CurrentUser UserPrincipal principal,
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody VaultTransferRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }

        InitiatedPayment payment = transferService.transfer(id, UUID.fromString(principal.userId()),
                request.direction(), request.walletAccountId(), request.amountMinor(), request.currency(),
                idempotencyKey.strip());
        return ResponseEntity.accepted().body(VaultTransferResponse.from(payment));
    }
}
