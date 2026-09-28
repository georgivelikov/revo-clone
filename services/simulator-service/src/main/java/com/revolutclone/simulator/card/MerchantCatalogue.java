package com.revolutclone.simulator.card;

import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * plans/phase-05-cards.md's Merchant simulator: "~30 merchants with realistic MCCs ... each with a
 * plausible amount range and currency." A handful settle in USD/GBP/JPY against what this project's
 * seed data treats as EUR wallets - "essential" per the same Design detail, since only a
 * foreign-currency merchant exercises the card→FX path (task 05.5) end to end.
 *
 * <p>A plain static list, not a database table: the catalogue is fixture data with no lifecycle of
 * its own, and this service owns no database (see docs/architecture.md) to put it in.
 */
public final class MerchantCatalogue {

    public static final List<Merchant> MERCHANTS = List.of(
            // 5411 groceries
            new Merchant("Tesco", "5411", "IE", "EUR", 500, 8_000),
            new Merchant("Lidl", "5411", "IE", "EUR", 400, 6_500),
            new Merchant("Aldi", "5411", "IE", "EUR", 400, 6_500),
            new Merchant("SuperValu", "5411", "IE", "EUR", 500, 9_000),
            new Merchant("Carrefour", "5411", "FR", "EUR", 500, 9_000),
            // 5812 restaurants
            new Merchant("Nando's", "5812", "IE", "EUR", 1_200, 4_500),
            new Merchant("Wagamama", "5812", "IE", "EUR", 1_500, 5_500),
            new Merchant("Five Guys", "5812", "IE", "EUR", 900, 3_000),
            new Merchant("Pizza Express", "5812", "IE", "EUR", 1_200, 4_800),
            new Merchant("Starbucks", "5812", "US", "USD", 400, 1_200),
            // 5814 fast food
            new Merchant("McDonald's", "5814", "IE", "EUR", 600, 2_000),
            new Merchant("Burger King", "5814", "IE", "EUR", 600, 2_200),
            // 4121 rideshare
            new Merchant("Uber", "4121", "US", "USD", 800, 4_500),
            new Merchant("Bolt", "4121", "EE", "EUR", 500, 3_500),
            new Merchant("FreeNow", "4121", "IE", "EUR", 500, 3_500),
            new Merchant("Lyft", "4121", "US", "USD", 800, 4_000),
            // 5999 misc / retail
            new Merchant("Amazon", "5999", "US", "USD", 1_000, 25_000),
            new Merchant("IKEA", "5999", "IE", "EUR", 1_500, 20_000),
            new Merchant("Zara", "5999", "ES", "EUR", 1_500, 15_000),
            new Merchant("H&M", "5999", "SE", "EUR", 1_000, 10_000),
            new Merchant("Decathlon", "5999", "FR", "EUR", 1_000, 12_000),
            new Merchant("ASOS", "5999", "GB", "GBP", 1_200, 15_000),
            new Merchant("Marks & Spencer", "5999", "GB", "GBP", 1_000, 12_000),
            new Merchant("Apple Store", "5999", "US", "USD", 5_000, 250_000),
            new Merchant("Nintendo eShop", "5999", "JP", "JPY", 500, 8_000),
            new Merchant("Rakuten", "5999", "JP", "JPY", 500, 15_000),
            // 5541 fuel
            new Merchant("Circle K", "5541", "IE", "EUR", 3_000, 9_000),
            new Merchant("Applegreen", "5541", "IE", "EUR", 3_000, 9_000),
            new Merchant("Shell", "5541", "GB", "GBP", 3_000, 9_000),
            // 4511 airlines
            new Merchant("Ryanair", "4511", "IE", "EUR", 3_000, 35_000),
            new Merchant("Delta", "4511", "US", "USD", 8_000, 60_000),
            new Merchant("British Airways", "4511", "GB", "GBP", 8_000, 60_000),
            // 7011 hotels
            new Merchant("Marriott", "7011", "US", "USD", 8_000, 50_000),
            new Merchant("Premier Inn", "7011", "GB", "GBP", 5_000, 25_000),
            // 5815 digital goods / streaming
            new Merchant("Netflix", "5815", "US", "USD", 999, 1_999),
            new Merchant("Spotify", "5815", "SE", "EUR", 999, 1_699));

    private MerchantCatalogue() {
    }

    public static Merchant random(RandomGenerator random) {
        return MERCHANTS.get(random.nextInt(MERCHANTS.size()));
    }

    public static Optional<Merchant> byName(String name) {
        return MERCHANTS.stream().filter(merchant -> merchant.name().equalsIgnoreCase(name)).findFirst();
    }
}
