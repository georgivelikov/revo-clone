package com.revolutclone.analytics.categorisation;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets (or replaces) a user's own category correction for one merchant - the highest-priority
 * layer {@link CategorisationResolver} consults. Task 06.2's scope only makes this affect
 * <em>future</em> categorisation, per the task's own boundary: it does not touch any
 * already-assigned {@link TransactionCategory} row, {@code monthly_spend}, or any other
 * aggregate. Task 06.4's retro-apply is what corrects history once an override is set here.
 */
@Component
public class MerchantCategoryOverrideService {

    private final MerchantCategoryOverrideRepository overrides;

    public MerchantCategoryOverrideService(MerchantCategoryOverrideRepository overrides) {
        this.overrides = overrides;
    }

    /**
     * {@code Instant.now()} below is deliberate, not an oversight of the phase's "no {@code
     * now()} in a projection" rule: this method is a user command, not a Kafka projection
     * consumer, and {@code updated_at} only ever records when the user acted, never anything a
     * replay recomputes.
     */
    @Transactional
    public MerchantCategoryOverride setOverride(UUID userId, String merchantName, String category) {
        if (!Category.ALL.contains(category)) {
            throw new InvalidCategoryException(category);
        }
        // Replace-in-place rather than a blind insert: one row per (user, merchant name) is the
        // table's own invariant (V5__categorisation.sql), and a second call for the same pair is
        // a correction of the first, not a duplicate.
        overrides.findByUserIdAndMerchantNameIgnoreCase(userId, merchantName)
                .ifPresent(overrides::delete);
        return overrides.save(new MerchantCategoryOverride(userId, merchantName, category, Instant.now()));
    }
}
