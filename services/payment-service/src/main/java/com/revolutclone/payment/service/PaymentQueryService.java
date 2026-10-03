package com.revolutclone.payment.service;

import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import com.revolutclone.payment.readmodel.AccountView;
import com.revolutclone.payment.readmodel.AccountViewRepository;
import com.revolutclone.payment.web.error.PaymentNotFoundException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@code GET /api/payments} and {@code GET /api/payments/{id}} (task 09.6; documented in
 * plans/phase-03-payments-and-ui.md's endpoint table since task 03.1 but never implemented - the
 * gap went unnoticed because nothing exercised it until this task's Activity screen and the
 * WebSocket-drop poll fallback from task 03.10 both started calling it).
 *
 * <p>"Mine" means <em>either side</em> of the transfer, not just the one this service happens to
 * call {@code initiatorUserId}: a payment the caller received is exactly as much their activity
 * as one they sent. {@link AccountViewRepository} - payment-service's own local read model of
 * which account belongs to which user, already built for {@code POST /api/payments}'s validation
 * - is what makes that resolvable without asking account-service (CLAUDE.md rule 7).
 */
@Service
public class PaymentQueryService {

    private final PaymentRepository payments;
    private final AccountViewRepository accountViews;

    public PaymentQueryService(PaymentRepository payments, AccountViewRepository accountViews) {
        this.payments = payments;
        this.accountViews = accountViews;
    }

    @Transactional(readOnly = true)
    public List<Payment> feedFor(UUID userId) {
        Set<UUID> ownAccountIds = accountViews.findByUserId(userId).stream()
                .map(AccountView::getAccountId)
                .collect(Collectors.toSet());
        if (ownAccountIds.isEmpty()) {
            return List.of();
        }
        return payments.findBySourceAccountIdInOrTargetAccountIdInOrderByCreatedAtDesc(
                ownAccountIds, ownAccountIds);
    }

    @Transactional(readOnly = true)
    public Payment getVisibleTo(UUID userId, UUID paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (!isVisibleTo(payment, userId)) {
            throw new PaymentNotFoundException(paymentId);
        }
        return payment;
    }

    private boolean isVisibleTo(Payment payment, UUID userId) {
        if (payment.getInitiatorUserId().equals(userId)) {
            return true;
        }
        return accountViews.findByUserId(userId).stream()
                .map(AccountView::getAccountId)
                .anyMatch(accountId -> accountId.equals(payment.getSourceAccountId())
                        || accountId.equals(payment.getTargetAccountId()));
    }
}
