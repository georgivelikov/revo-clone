package com.revolutclone.analytics.categorisation;

/**
 * The fixed category set from plans/phase-06-vaults-analytics.md's "Categorisation" section,
 * spelled exactly as the phase names them. Nothing here is configurable - a category outside
 * this set cannot be stored, by construction (see {@link MerchantCategoryOverrideService}).
 */
public final class Category {

    public static final String GROCERIES = "Groceries";
    public static final String RESTAURANTS = "Restaurants";
    public static final String TRANSPORT = "Transport";
    public static final String SHOPPING = "Shopping";
    public static final String ENTERTAINMENT = "Entertainment";
    public static final String BILLS = "Bills";
    public static final String HEALTH = "Health";
    public static final String TRAVEL = "Travel";
    public static final String TRANSFERS = "Transfers";
    public static final String CASH = "Cash";
    public static final String INCOME = "Income";

    /** The fall-through when none of the three resolution layers match anything. */
    public static final String OTHER = "Other";

    public static final java.util.Set<String> ALL = java.util.Set.of(GROCERIES, RESTAURANTS, TRANSPORT, SHOPPING,
            ENTERTAINMENT, BILLS, HEALTH, TRAVEL, TRANSFERS, CASH, INCOME, OTHER);

    private Category() {
    }
}
