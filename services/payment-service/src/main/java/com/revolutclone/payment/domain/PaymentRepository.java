package com.revolutclone.payment.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link #findByInitiatorUserIdAndIdempotencyKey} backs idempotency layer 2 from
 * plans/phase-03-payments-and-ui.md: the {@code unique (initiator_user_id, idempotency_key)}
 * constraint is what actually guarantees correctness, and this is how a caught constraint
 * violation is turned back into the existing payment (task 03.3). Redis at the edge is an
 * optimisation and may be flushed at any time.
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByInitiatorUserIdAndIdempotencyKey(UUID initiatorUserId, String idempotencyKey);
}
