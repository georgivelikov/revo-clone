package com.revolutclone.simulator.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * plans/phase-05-cards.md's Merchant simulator: "~30 merchants with realistic MCCs ... each with a
 * plausible amount range and currency," plus "foreign-currency merchants are essential."
 */
class MerchantCatalogueTest {

    @Test
    void hasAroundThirtyDistinctlyNamedMerchants() {
        assertThat(MerchantCatalogue.MERCHANTS).hasSizeGreaterThanOrEqualTo(28);
        Set<String> names = MerchantCatalogue.MERCHANTS.stream().map(Merchant::name).collect(Collectors.toSet());
        assertThat(names).hasSameSizeAs(MerchantCatalogue.MERCHANTS);
    }

    @Test
    void everyMerchantHasSchemaShapedMccAndCountry() {
        assertThat(MerchantCatalogue.MERCHANTS).allSatisfy(merchant -> {
            assertThat(merchant.mcc()).hasSize(4);
            assertThat(merchant.country()).hasSize(2);
            assertThat(merchant.currency()).hasSize(3);
            assertThat(merchant.minAmountMinor()).isPositive();
            assertThat(merchant.maxAmountMinor()).isGreaterThanOrEqualTo(merchant.minAmountMinor());
        });
    }

    @Test
    void atLeastAFewMerchantsSettleOutsideEur() {
        assertThat(MerchantCatalogue.MERCHANTS.stream().map(Merchant::currency).distinct())
                .as("the card->FX path (task 05.5) is only exercised by a non-EUR merchant")
                .contains("USD", "GBP", "JPY");
    }

    @Test
    void randomAlwaysReturnsAnEntryFromTheCatalogue() {
        Merchant merchant = MerchantCatalogue.random(new Random(42));

        assertThat(MerchantCatalogue.MERCHANTS).contains(merchant);
    }

    @Test
    void byNameIsCaseInsensitiveAndFailsClosedOnAnUnknownName() {
        assertThat(MerchantCatalogue.byName("tesco")).contains(
                MerchantCatalogue.MERCHANTS.stream().filter(m -> m.name().equals("Tesco")).findFirst().orElseThrow());
        assertThat(MerchantCatalogue.byName("Not A Real Merchant")).isEmpty();
    }

    @Test
    void randomAmountMinorStaysWithinTheMerchantsOwnRange() {
        Merchant merchant = new Merchant("Test Merchant", "5999", "IE", "EUR", 100, 105);
        Random random = new Random(7);

        for (int i = 0; i < 50; i++) {
            long amount = merchant.randomAmountMinor(random);
            assertThat(amount).isBetween(100L, 105L);
        }
    }
}
