package com.revolutclone.analytics.consumer;

import com.revolutclone.analytics.categorisation.Category;
import com.revolutclone.analytics.categorisation.TransactionCategoryRepository;
import com.revolutclone.analytics.spend.MonthlySpendRepository;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationSettledV1;
import com.revolutclone.messaging.api.EventHandler;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code monthly_spend}'s incremental upsert (task 06.3) - the non-idempotent half of settling a
 * card authorization. {@link AuthorizationSettledEventHandler} wraps this in an
 * {@link com.revolutclone.messaging.idempotent.IdempotentHandler} before calling it, unlike that
 * class's own {@code CardAuthorizationFact} update: an upsert-by-primary-key is naturally
 * redelivery-safe, but adding {@code settledMinor} into a running total is not - the phase's own
 * "Incremental aggregates drift if an event is processed twice" warning - so a duplicate delivery
 * has to be caught here rather than relied on to be harmless by construction.
 *
 * <p><strong>Card spend only, deliberately.</strong> {@code ledger.entry.v1}'s postings carry a
 * ledger account id, not a {@code userId} (see task 06.1's own documented gap on
 * {@code ledger_entry_fact}), so there is no clean join back to a user yet for transfers, income or
 * cash. {@link AuthorizationSettledV1} already carries {@code userId} directly, so this projection
 * is built from it rather than from the ledger topic; three of the phase's twelve categories
 * (Transfers, Income, Cash) are therefore not populated by any handler yet, left for whichever
 * later task solves the account-to-user join.
 *
 * <p><strong>No wall clock.</strong> The month bucket is derived solely from
 * {@code envelope.occurredAt()} - the event's own timestamp, UTC, truncated to the first of the
 * month - never {@link java.time.Clock} or {@code Instant.now()}. A replayed event with an old
 * {@code occurredAt} must land in its own historical month, not the month the replay happens to
 * run in.
 *
 * <p>Reads the category {@code TransactionCategorisationService} already assigned at approval time
 * rather than recomputing it - by the time any authorization reaches settlement, its
 * {@code transaction_category} row already exists (see
 * {@link AuthorizationApprovedEventHandler}). Uses {@code settledMinor}/{@code settledCurrency} -
 * the wallet-currency figure - never {@code merchantSettledMinor}/{@code currency}, which is the
 * merchant's own currency and would silently mix currencies into one bucket.
 */
@Component
public class MonthlySpendProjectionHandler implements EventHandler<AuthorizationSettledV1> {

    private static final Logger log = LoggerFactory.getLogger(MonthlySpendProjectionHandler.class);

    private final TransactionCategoryRepository categories;
    private final MonthlySpendRepository monthlySpend;

    public MonthlySpendProjectionHandler(TransactionCategoryRepository categories,
            MonthlySpendRepository monthlySpend) {
        this.categories = categories;
        this.monthlySpend = monthlySpend;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<AuthorizationSettledV1> envelope) {
        AuthorizationSettledV1 event = envelope.payload();
        String category = categories.findById(event.authorizationId())
                .map(transactionCategory -> transactionCategory.getCategory())
                .orElseGet(() -> {
                    // Same honest gap CardAuthorizationFact documents for the identical situation:
                    // this service started consuming after the approval fell out of the topic's
                    // retention window, so there was never a chance to categorise it. Recorded as
                    // Other rather than dropping the settlement's spend from the aggregate entirely.
                    log.warn("settlement {} has no transaction_category row; recording its spend under {}",
                            event.authorizationId(), Category.OTHER);
                    return Category.OTHER;
                });
        LocalDate month = envelope.occurredAt().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        monthlySpend.increment(event.userId(), month, category, event.settledCurrency(), event.settledMinor());
    }
}
