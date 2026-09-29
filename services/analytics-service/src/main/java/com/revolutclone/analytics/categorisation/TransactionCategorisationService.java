package com.revolutclone.analytics.categorisation;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigns a category the first time an authorization's merchant details are known - called from
 * {@link com.revolutclone.analytics.consumer.AuthorizationApprovedEventHandler}, the one event
 * in {@code card.authorization.v1} that carries {@code merchantName}/{@code merchantMcc} for the
 * first time (see that class's own javadoc).
 *
 * <p>Idempotent by construction rather than by an {@code IdempotentHandler} wrapper: a
 * redelivered {@code AuthorizationApprovedV1} finds a row already here and does nothing, the
 * same reasoning {@code AuthorizationApprovedEventHandler} itself already relies on for
 * {@code card_authorization_fact}. Never updates an existing row - task 06.4's retro-apply, not
 * this task, is what changes a category after it has been assigned once.
 */
@Component
public class TransactionCategorisationService {

    private final CategorisationResolver resolver;
    private final TransactionCategoryRepository categories;

    public TransactionCategorisationService(CategorisationResolver resolver, TransactionCategoryRepository categories) {
        this.resolver = resolver;
        this.categories = categories;
    }

    @Transactional
    public void categoriseIfAbsent(UUID authorizationId, UUID userId, String merchantName, String mcc,
            Instant assignedAt) {
        if (categories.existsById(authorizationId)) {
            return;
        }
        CategorisationOutcome outcome = resolver.resolve(userId, merchantName, mcc);
        categories.save(new TransactionCategory(authorizationId, userId, outcome.category(), outcome.source(),
                assignedAt));
    }
}
