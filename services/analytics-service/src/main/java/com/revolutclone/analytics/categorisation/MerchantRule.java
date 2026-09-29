package com.revolutclone.analytics.categorisation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The middle layer of categorisation: a name pattern (SQL {@code LIKE} syntax - {@code %} for
 * any run of characters, {@code _} for any single character) mapped to a {@link Category}.
 * {@link CategorisationResolver} matches {@link #pattern} against a merchant name
 * case-insensitively; {@link #priority} breaks ties when more than one seeded pattern matches
 * the same name, lower running first. Seeded by {@code V5__categorisation.sql}; the application
 * never writes to this table, only reads it via {@link MerchantRuleRepository}.
 */
@Entity
@Table(name = "merchant_rule")
public class MerchantRule {

    @Id
    private String pattern;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private int priority;

    protected MerchantRule() {
        // JPA
    }

    /** The application never calls this in production - rows come from {@code V5__categorisation.sql} - but
     * tests need a way to seed fixtures without another Flyway migration per test case. */
    public MerchantRule(String pattern, String category, int priority) {
        this.pattern = pattern;
        this.category = category;
        this.priority = priority;
    }

    public String getPattern() {
        return pattern;
    }

    public String getCategory() {
        return category;
    }

    public int getPriority() {
        return priority;
    }
}
