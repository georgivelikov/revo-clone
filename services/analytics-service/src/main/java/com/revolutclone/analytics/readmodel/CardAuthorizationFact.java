package com.revolutclone.analytics.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * One row per card authorization that actually held funds, upserted across its lifecycle by
 * the four handlers in {@link com.revolutclone.analytics.consumer} that read
 * {@code card.authorization.v1}: approved, (partially) settled, reversed, expired. See that
 * package's {@code CardAuthorizationKafkaListener} javadoc for why a decline never reaches
 * this table at all.
 *
 * <p>Every field but the merchant/amount ones is carried by all four events this service
 * projects ({@code cardId}, {@code userId}, {@code accountId}, {@code panLast4}), so in the
 * normal case - approval always precedes its own settlement/reversal/expiry on the same
 * {@code cardId} partition, per CLAUDE.md rule 5 - a settlement/reversal/expiry only ever
 * updates a row {@link com.revolutclone.analytics.consumer.AuthorizationApprovedEventHandler}
 * already created. {@link #applySettled}/{@link #applyReversed}/{@link #applyExpired} still
 * re-supply those identity fields and backfill {@link #authorizedAt} if it is somehow still
 * unset, so a row created defensively (this service started consuming after the authorization's
 * own approval fell out of the topic's retention window) is still usable rather than half-null -
 * an honest, logged gap rather than a crash, the same trade notification-service's
 * {@code EntryPostedEventHandler} documents for the identical situation.
 */
@Entity
@Table(name = "card_authorization_fact")
public class CardAuthorizationFact implements Persistable<UUID> {

    public static final String APPROVED = "APPROVED";
    public static final String REVERSED = "REVERSED";
    public static final String EXPIRED = "EXPIRED";

    @Id
    @Column(name = "authorization_id")
    private UUID authorizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "card_id", nullable = false)
    private UUID cardId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "pan_last4", nullable = false)
    private String panLast4;

    @Column(name = "merchant_name")
    private String merchantName;

    @Column(name = "merchant_mcc")
    private String merchantMcc;

    @Column(name = "merchant_country")
    private String merchantCountry;

    /** Merchant-currency amount, as authorized. Null only on a defensively-created row. */
    @Column(name = "amount_minor")
    private Long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3, columnDefinition = "char(3)")
    private String currency;

    /** Wallet-currency amount held, as authorized. Null only on a defensively-created row. */
    @Column(name = "billing_minor")
    private Long billingMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "billing_currency", length = 3, columnDefinition = "char(3)")
    private String billingCurrency;

    /** Wallet-currency amount actually settled, once known - null until a settlement arrives. */
    @Column(name = "settled_minor")
    private Long settledMinor;

    @Column(nullable = false)
    private String state;

    @Column(name = "authorized_at", nullable = false)
    private Instant authorizedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    /** {@code occurredAt} of the last event applied here - event time, never wall clock. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected CardAuthorizationFact() {
        // JPA
    }

    public CardAuthorizationFact(UUID authorizationId) {
        this.authorizationId = authorizationId;
    }

    public void applyApproved(UUID cardId, UUID userId, UUID accountId, String panLast4, String merchantName,
            String merchantMcc, String merchantCountry, long amountMinor, String currency, long billingMinor,
            String billingCurrency, Instant occurredAt) {
        this.cardId = cardId;
        this.userId = userId;
        this.accountId = accountId;
        this.panLast4 = panLast4;
        this.merchantName = merchantName;
        this.merchantMcc = merchantMcc;
        this.merchantCountry = merchantCountry;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.billingMinor = billingMinor;
        this.billingCurrency = billingCurrency;
        this.state = APPROVED;
        this.authorizedAt = occurredAt;
        this.updatedAt = occurredAt;
    }

    /** {@code state} is the wire value straight off {@code AuthorizationSettledV1} - {@code SETTLED} or
     * {@code PARTIALLY_SETTLED} - so this row need not re-derive it. */
    public void applySettled(UUID cardId, UUID userId, UUID accountId, String panLast4, String merchantName,
            String merchantMcc, String merchantCountry, String state, long settledMinor, Instant occurredAt) {
        ensureIdentity(cardId, userId, accountId, panLast4, merchantName, merchantMcc, merchantCountry, occurredAt);
        this.state = state;
        this.settledMinor = settledMinor;
        this.settledAt = occurredAt;
        this.updatedAt = occurredAt;
    }

    public void applyReversed(UUID cardId, UUID userId, UUID accountId, String panLast4, String merchantName,
            Instant occurredAt) {
        ensureIdentity(cardId, userId, accountId, panLast4, merchantName, null, null, occurredAt);
        this.state = REVERSED;
        this.updatedAt = occurredAt;
    }

    public void applyExpired(UUID cardId, UUID userId, UUID accountId, String panLast4, String merchantName,
            Instant occurredAt) {
        ensureIdentity(cardId, userId, accountId, panLast4, merchantName, null, null, occurredAt);
        this.state = EXPIRED;
        this.updatedAt = occurredAt;
    }

    private void ensureIdentity(UUID cardId, UUID userId, UUID accountId, String panLast4, String merchantName,
            String merchantMcc, String merchantCountry, Instant occurredAt) {
        this.cardId = cardId;
        this.userId = userId;
        this.accountId = accountId;
        this.panLast4 = panLast4;
        if (this.merchantName == null) {
            this.merchantName = merchantName;
        }
        if (this.merchantMcc == null) {
            this.merchantMcc = merchantMcc;
        }
        if (this.merchantCountry == null) {
            this.merchantCountry = merchantCountry;
        }
        if (this.authorizedAt == null) {
            this.authorizedAt = occurredAt;
        }
    }

    @Override
    @Transient
    public UUID getId() {
        return authorizationId;
    }

    @Override
    @Transient
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getAuthorizationId() {
        return authorizationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getCardId() {
        return cardId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getPanLast4() {
        return panLast4;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public String getMerchantMcc() {
        return merchantMcc;
    }

    public String getMerchantCountry() {
        return merchantCountry;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getBillingMinor() {
        return billingMinor;
    }

    public String getBillingCurrency() {
        return billingCurrency;
    }

    public Long getSettledMinor() {
        return settledMinor;
    }

    public String getState() {
        return state;
    }

    public Instant getAuthorizedAt() {
        return authorizedAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
