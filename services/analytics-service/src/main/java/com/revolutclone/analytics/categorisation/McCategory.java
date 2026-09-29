package com.revolutclone.analytics.categorisation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The fallback layer of categorisation: one ISO 18245 merchant category code mapped to one
 * {@link Category}. Seeded entirely by {@code V5__categorisation.sql} - the application never
 * writes to this table, only reads it via {@link McCategoryRepository}.
 */
@Entity
@Table(name = "mcc_category")
public class McCategory {

    @Id
    private String mcc;

    @Column(nullable = false)
    private String category;

    protected McCategory() {
        // JPA
    }

    /** The application never calls this in production - rows come from {@code V5__categorisation.sql} - but
     * tests need a way to seed fixtures without another Flyway migration per test case. */
    public McCategory(String mcc, String category) {
        this.mcc = mcc;
        this.category = category;
    }

    public String getMcc() {
        return mcc;
    }

    public String getCategory() {
        return category;
    }
}
