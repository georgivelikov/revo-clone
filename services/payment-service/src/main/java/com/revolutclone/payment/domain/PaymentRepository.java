package com.revolutclone.payment.domain;

import java.util.Collection;
import java.util.List;
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

    /**
     * The correlation a cross-currency transfer's ledger result is matched back through:
     * {@code fxquote:<quoteId>} is the ledger key, not {@code payment:<paymentId>}, because the
     * entry this posts is {@code FX}-typed and captures fx-service's reservation - see
     * {@code CrossCurrencyEntryPostedHandler}/{@code CrossCurrencyEntryRejectedHandler}.
     */
    Optional<Payment> findByQuoteId(UUID quoteId);

    /**
     * {@code GET /api/payments}'s feed (task 09.6): every payment that moved money into or out of
     * one of the caller's own accounts, newest first. Two account-id sets rather than one userId
     * column, because a payment only ever records the accounts it moved money between - the
     * caller is "mine" by owning one of those accounts, in either direction, which is what
     * {@link PaymentQueryService} resolves via {@code AccountViewRepository.findByUserId} before
     * calling this.
     */
    List<Payment> findBySourceAccountIdInOrTargetAccountIdInOrderByCreatedAtDesc(
            Collection<UUID> sourceAccountIds, Collection<UUID> targetAccountIds);
}
