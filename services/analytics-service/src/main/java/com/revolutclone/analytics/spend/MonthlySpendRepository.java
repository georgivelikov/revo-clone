package com.revolutclone.analytics.spend;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonthlySpendRepository extends JpaRepository<MonthlySpend, MonthlySpend.Key> {

    /**
     * Every currency bucket a user has for one month/category - the shape
     * {@code MultiCurrencySpendTest} needs to prove two currencies stay as two rows rather than
     * being silently summed into one.
     */
    List<MonthlySpend> findByUserIdAndMonthAndCategory(UUID userId, LocalDate month, String category);

    /**
     * plans/phase-06-vaults-analytics.md's own upsert, verbatim: {@code total_minor = total_minor
     * + excluded.total_minor}. A native {@code ON CONFLICT} rather than a JPA read-modify-write,
     * so two settlements landing in the same {@code (user_id, month, category, currency)} bucket
     * at the same time - plausible, since different authorizations key {@code card.authorization.v1}
     * by {@code cardId}, not by this bucket - cannot lose an update to a race. The database's own
     * row lock does the serialising; there is nothing for application code to get wrong here.
     *
     * <p>{@code txn_count} increments by exactly one per call, one call per settlement - the
     * caller (see {@code MonthlySpendProjectionHandler}) is expected to invoke this once per
     * distinct settled authorization, which is exactly what wrapping it in an
     * {@code IdempotentHandler} guarantees against redelivery.
     */
    @Modifying
    @Query(value = """
            insert into monthly_spend (user_id, month, category, currency, total_minor, txn_count)
            values (:userId, :month, :category, :currency, :amountMinor, 1)
            on conflict (user_id, month, category, currency)
            do update set total_minor = monthly_spend.total_minor + excluded.total_minor,
                          txn_count = monthly_spend.txn_count + excluded.txn_count
            """, nativeQuery = true)
    void increment(@Param("userId") UUID userId, @Param("month") LocalDate month,
            @Param("category") String category, @Param("currency") String currency,
            @Param("amountMinor") long amountMinor);

    /**
     * Task 06.4's retro-apply correction: undoes exactly one settlement's earlier contribution to
     * a {@code (user, month, category, currency)} bucket, the opposite of {@link #increment} for
     * a transaction that has just been recategorised away from that bucket. A plain {@code UPDATE},
     * not an upsert - the bucket is expected to already exist (it was created by the very
     * {@code increment} call this reverses), so there is nothing sensible to insert if it somehow
     * does not.
     */
    @Modifying
    @Query(value = """
            update monthly_spend
            set total_minor = total_minor - :amountMinor, txn_count = txn_count - 1
            where user_id = :userId and month = :month and category = :category and currency = :currency
            """, nativeQuery = true)
    void decrement(@Param("userId") UUID userId, @Param("month") LocalDate month,
            @Param("category") String category, @Param("currency") String currency,
            @Param("amountMinor") long amountMinor);
}
