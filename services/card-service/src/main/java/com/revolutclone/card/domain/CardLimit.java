package com.revolutclone.card.domain;

import com.revolutclone.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One spending ceiling on one card, mirroring plans/phase-05-cards.md's {@code card_limit} table: the
 * primary key is {@code (card_id, period)}, so a card has at most one limit per period and the schema
 * itself rules out two contradictory daily limits.
 *
 * <p><strong>A limit is an amount of money, so it carries a currency</strong> - and that currency is the
 * <em>wallet's</em>, not the merchant's. A card used at a dollar merchant is still limited in the euro
 * its wallet is denominated in, which is why {@code CardLimitEvaluator} compares against the billing
 * amount from step 4 rather than the amount the merchant asked for. A limit stored in some third
 * currency is a misconfiguration, and {@link #amount()} feeding {@link Money}'s arithmetic is what turns
 * it into a loud {@code CurrencyMismatchException} instead of a silently wrong comparison.
 *
 * <p>Absence means absence: a card with no row for a period has no ceiling for that period. That is a
 * deliberate choice over a defaulted ceiling, because a default would be a policy invented here and
 * invisible in the data - a holder asking "what is my daily limit?" should get the answer from the same
 * place the evaluator gets it.
 */
@Entity
@Table(name = "card_limit")
@IdClass(CardLimit.Key.class)
public class CardLimit {

    @Id
    @Column(name = "card_id", nullable = false)
    private UUID cardId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LimitPeriod period;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    protected CardLimit() {
        // JPA
    }

    public CardLimit(UUID cardId, LimitPeriod period, long amountMinor, String currency) {
        this.cardId = cardId;
        this.period = period;
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    /** The ceiling as money, which is the only form anything is allowed to compare it in. */
    public Money amount() {
        return Money.of(amountMinor, currency);
    }

    public UUID getCardId() {
        return cardId;
    }

    public LimitPeriod getPeriod() {
        return period;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    /**
     * The composite key, as a JPA {@code @IdClass} rather than a record: the specification requires a
     * no-argument constructor and mutable fields on an id class, which a record cannot offer.
     */
    public static class Key implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private UUID cardId;
        private LimitPeriod period;

        public Key() {
            // JPA
        }

        public Key(UUID cardId, LimitPeriod period) {
            this.cardId = cardId;
            this.period = period;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key && Objects.equals(cardId, key.cardId) && period == key.period;
        }

        @Override
        public int hashCode() {
            return Objects.hash(cardId, period);
        }

        @Override
        public String toString() {
            return cardId + "/" + period;
        }
    }
}
