package com.revolutclone.analytics.categorisation;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.analytics.testsupport.NoOpJwtDecoderTestConfig;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-06-vaults-analytics.md's named test for task 06.2: "User override beats merchant
 * rule beats MCC." Exercises {@link CategorisationResolver} directly (the pure three-layer
 * decision) and {@link TransactionCategorisationService} (the write path that assigns a category
 * exactly once, from {@link com.revolutclone.analytics.consumer.AuthorizationApprovedEventHandler}).
 *
 * <p>Fixtures use fictional MCCs/patterns that do not collide with {@code V5__categorisation.sql}'s
 * seed data, so this test's own assertions do not depend on - or get confused by - what the real
 * seed rows happen to say. {@link #seededDataResolvesRealisticMerchants()} separately proves the
 * seed data itself is sane.
 */
@Testcontainers
@SpringBootTest
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class CategorisationPriorityTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private CategorisationResolver resolver;

    @Autowired
    private MerchantRuleRepository merchantRules;

    @Autowired
    private McCategoryRepository mccCategories;

    @Autowired
    private MerchantCategoryOverrideRepository overrides;

    @Autowired
    private TransactionCategorisationService categorisationService;

    @Autowired
    private TransactionCategoryRepository transactionCategories;

    @Test
    void userOverrideBeatsMerchantRuleBeatsMcc() {
        UUID userId = UUID.randomUUID();
        String merchantName = "ACME SUPERSTORE 42";
        String mcc = "9911"; // fictional, not in the seed set

        mccCategories.save(new McCategory(mcc, Category.HEALTH));
        merchantRules.save(new MerchantRule("ACME%", Category.SHOPPING, 100));

        // Layer 3 alone: no rule matches, the MCC wins.
        CategorisationOutcome mccOnly = resolver.resolve(userId, "Somewhere Else Entirely", mcc);
        assertThat(mccOnly.category()).isEqualTo(Category.HEALTH);
        assertThat(mccOnly.source()).isEqualTo(CategorisationSource.MCC);

        // Layer 2 beats layer 3: the merchant rule matches, even though the MCC also resolves.
        CategorisationOutcome ruleBeatsMcc = resolver.resolve(userId, merchantName, mcc);
        assertThat(ruleBeatsMcc.category()).isEqualTo(Category.SHOPPING);
        assertThat(ruleBeatsMcc.source()).isEqualTo(CategorisationSource.MERCHANT_RULE);

        // Layer 1 beats layer 2: a user override for this exact merchant wins over the rule.
        overrides.save(new MerchantCategoryOverride(userId, merchantName, Category.TRAVEL, Instant.now()));
        CategorisationOutcome userWins = resolver.resolve(userId, merchantName, mcc);
        assertThat(userWins.category()).isEqualTo(Category.TRAVEL);
        assertThat(userWins.source()).isEqualTo(CategorisationSource.USER);

        // The override is scoped to this user - a different user still gets the merchant rule.
        CategorisationOutcome otherUser = resolver.resolve(UUID.randomUUID(), merchantName, mcc);
        assertThat(otherUser.category()).isEqualTo(Category.SHOPPING);
        assertThat(otherUser.source()).isEqualTo(CategorisationSource.MERCHANT_RULE);
    }

    @Test
    void merchantRuleMatchingIsCaseInsensitive() {
        merchantRules.save(new MerchantRule("BREW%", Category.RESTAURANTS, 100));

        CategorisationOutcome outcome = resolver.resolve(UUID.randomUUID(), "brew house cafe", null);

        assertThat(outcome.category()).isEqualTo(Category.RESTAURANTS);
        assertThat(outcome.source()).isEqualTo(CategorisationSource.MERCHANT_RULE);
    }

    @Test
    void unresolvedMerchantAndMccFallBackToOtherWithDefaultSource() {
        CategorisationOutcome outcome = resolver.resolve(UUID.randomUUID(), "Totally Unknown Merchant Ltd", "0000");

        assertThat(outcome.category()).isEqualTo(Category.OTHER);
        assertThat(outcome.source()).isEqualTo(CategorisationSource.DEFAULT);
    }

    @Test
    void aNullMerchantNameSkipsStraightToMccThenDefault() {
        String mcc = "9922";
        mccCategories.save(new McCategory(mcc, Category.BILLS));

        CategorisationOutcome resolved = resolver.resolve(UUID.randomUUID(), null, mcc);
        assertThat(resolved.category()).isEqualTo(Category.BILLS);
        assertThat(resolved.source()).isEqualTo(CategorisationSource.MCC);

        CategorisationOutcome fallback = resolver.resolve(UUID.randomUUID(), null, null);
        assertThat(fallback.category()).isEqualTo(Category.OTHER);
        assertThat(fallback.source()).isEqualTo(CategorisationSource.DEFAULT);
    }

    @Test
    void categoriseIfAbsentAssignsOnceAndIgnoresLaterCallsForTheSameAuthorization() {
        UUID authorizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String merchantName = "First Sight Merchant";
        String mcc = "9933";
        Instant occurredAt = Instant.parse("2024-05-01T00:00:00Z");
        mccCategories.save(new McCategory(mcc, Category.TRANSPORT));

        categorisationService.categoriseIfAbsent(authorizationId, userId, merchantName, mcc, occurredAt);

        TransactionCategory first = transactionCategories.findById(authorizationId).orElseThrow();
        assertThat(first.getCategory()).isEqualTo(Category.TRANSPORT);
        assertThat(first.getSource()).isEqualTo(CategorisationSource.MCC);
        assertThat(first.getUserId()).isEqualTo(userId);
        assertThat(first.getAssignedAt()).isEqualTo(occurredAt);

        // A later override for this merchant must not retroactively change a row already
        // written - task 06.2 only affects future categorisation (task 06.4 is retro-apply).
        overrides.save(new MerchantCategoryOverride(userId, merchantName, Category.SHOPPING, Instant.now()));
        categorisationService.categoriseIfAbsent(authorizationId, userId, merchantName, mcc, occurredAt);

        TransactionCategory afterRedelivery = transactionCategories.findById(authorizationId).orElseThrow();
        assertThat(afterRedelivery.getCategory()).isEqualTo(Category.TRANSPORT);
        assertThat(afterRedelivery.getSource()).isEqualTo(CategorisationSource.MCC);
        assertThat(transactionCategories.count()).isEqualTo(1);
    }

    @Test
    void seededDataResolvesRealisticMerchants() {
        // V5__categorisation.sql's own rows, not fixtures this test inserts.
        CategorisationOutcome tesco = resolver.resolve(UUID.randomUUID(), "Tesco Express London", "5411");
        assertThat(tesco.category()).isEqualTo(Category.GROCERIES);
        assertThat(tesco.source()).isEqualTo(CategorisationSource.MERCHANT_RULE);

        // No merchant rule for this name, but the MCC is seeded.
        CategorisationOutcome unknownRestaurant = resolver.resolve(UUID.randomUUID(), "Corner Bistro", "5812");
        assertThat(unknownRestaurant.category()).isEqualTo(Category.RESTAURANTS);
        assertThat(unknownRestaurant.source()).isEqualTo(CategorisationSource.MCC);
    }
}
