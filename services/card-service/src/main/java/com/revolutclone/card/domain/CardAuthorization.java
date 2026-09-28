package com.revolutclone.card.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Mirrors plans/phase-05-cards.md's {@code card_authorization} table exactly. One row per decision
 * the issuer made, approved or declined, and the record the settlement lifecycle later updates.
 *
 * <p><strong>{@code networkRef} is the idempotency anchor for the whole card flow</strong> and is
 * unique in the schema. The network genuinely resends, and a resend arrives with a fresh
 * {@code eventId}, so {@code eventId} deduplication alone would let one tap become two
 * authorizations. Settlements and reversals quote the same reference, which is why it - and not this
 * row's own id - is what every later message is matched against.
 *
 * <p><strong>{@code billingMinor}/{@code billingCurrency} are the wallet's side of the
 * authorization</strong>, {@code amountMinor}/{@code currency} the merchant's. They are nullable in
 * the schema because a decline reached before the amount was converted has no billing amount to
 * record; an approval always has both. For a foreign-currency merchant the two genuinely differ, and
 * the five {@code fx_*} columns are what explain the difference - see {@link CardFxDetail}, including
 * why the exchange spread and the card mark-up are recorded as two separate figures.
 *
 * <p><strong>{@code reservationId} is a hold, {@code ledgerEntryId} a posting, and an approval has
 * only the first.</strong> That is the whole point of the phase: the money is held, nothing has been
 * accounted for, and {@code ledgerEntryId} stays null until a settlement captures the hold.
 *
 * <p>{@code authorizedAt} is supplied by the caller rather than defaulted by the database or taken
 * from {@code Instant.now()} here, so the service's injected {@code Clock} is the single source of
 * decision time - which is what lets task 05.4's rolling limit windows be tested at a boundary
 * without waiting for one.
 */
@Entity
@Table(name = "card_authorization")
public class CardAuthorization implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "card_id", nullable = false)
    private UUID cardId;

    @Column(name = "network_ref", nullable = false)
    private String networkRef;

    @Column(name = "merchant_name", nullable = false)
    private String merchantName;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_mcc", nullable = false, length = 4, columnDefinition = "char(4)")
    private String merchantMcc;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_country", nullable = false, length = 2, columnDefinition = "char(2)")
    private String merchantCountry;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "billing_minor")
    private Long billingMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "billing_currency", length = 3, columnDefinition = "char(3)")
    private String billingCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthorizationState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "decline_reason")
    private DeclineReason declineReason;

    @Column(name = "fx_mid_rate")
    private String fxMidRate;

    @Column(name = "fx_exchange_rate")
    private String fxExchangeRate;

    @Column(name = "fx_spread_bps")
    private Integer fxSpreadBps;

    @Column(name = "fx_markup_bps")
    private Integer fxMarkupBps;

    @Column(name = "fx_markup_minor")
    private Long fxMarkupMinor;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "ledger_entry_id")
    private UUID ledgerEntryId;

    @Column(name = "settled_minor")
    private Long settledMinor;

    @Column(name = "excess_minor")
    private Long excessMinor;

    @Column(name = "excess_ledger_entry_id")
    private UUID excessLedgerEntryId;

    @Column(name = "authorized_at", nullable = false)
    private Instant authorizedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Transient
    private boolean isNew = true;

    protected CardAuthorization() {
        // JPA
    }

    private CardAuthorization(UUID id, UUID cardId, String networkRef, String merchantName, String merchantMcc,
            String merchantCountry, long amountMinor, String currency, Long billingMinor, String billingCurrency,
            CardFxDetail fx, AuthorizationState state, DeclineReason declineReason, UUID reservationId,
            Instant authorizedAt) {
        this.id = id;
        this.cardId = cardId;
        this.networkRef = networkRef;
        this.merchantName = merchantName;
        this.merchantMcc = merchantMcc;
        this.merchantCountry = merchantCountry;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.billingMinor = billingMinor;
        this.billingCurrency = billingCurrency;
        if (fx != null) {
            this.fxMidRate = fx.midRate();
            this.fxExchangeRate = fx.exchangeRate();
            this.fxSpreadBps = fx.spreadBps();
            this.fxMarkupBps = fx.markupBps();
            this.fxMarkupMinor = fx.markupMinor();
        }
        this.state = state;
        this.declineReason = declineReason;
        this.reservationId = reservationId;
        this.authorizedAt = authorizedAt;
    }

    /**
     * Step 7's yes: the funds named by {@code reservationId} are held and no posting exists.
     *
     * <p>{@code fx} is null for a merchant already charging in the wallet's currency, and the five FX
     * columns stay null with it - see {@link CardFxDetail}.
     */
    public static CardAuthorization approved(UUID id, UUID cardId, String networkRef, String merchantName,
            String merchantMcc, String merchantCountry, long amountMinor, String currency, long billingMinor,
            String billingCurrency, CardFxDetail fx, UUID reservationId, Instant authorizedAt) {
        return new CardAuthorization(id, cardId, networkRef, merchantName, merchantMcc, merchantCountry,
                amountMinor, currency, billingMinor, billingCurrency, fx, AuthorizationState.APPROVED, null,
                reservationId, authorizedAt);
    }

    /**
     * Step 7's no. Recorded rather than merely published: a decline is what the per-card feed shows
     * the holder when they ask why their card did not work, and what task 05.4's rolling windows have
     * to be able to exclude from spend.
     *
     * <p>{@code billingMinor}/{@code billingCurrency} and {@code fx} are whatever was known when the
     * chain stopped - all null for a decline reached before the merchant amount was converted (a frozen
     * or expired card), all populated for one reached after it (over a limit, out of money). A holder
     * asking why a foreign purchase was refused is owed the amount it would have cost them, which is a
     * number that only exists once the conversion has been done.
     */
    public static CardAuthorization declined(UUID id, UUID cardId, String networkRef, String merchantName,
            String merchantMcc, String merchantCountry, long amountMinor, String currency, Long billingMinor,
            String billingCurrency, CardFxDetail fx, DeclineReason reason, Instant authorizedAt) {
        return new CardAuthorization(id, cardId, networkRef, merchantName, merchantMcc, merchantCountry,
                amountMinor, currency, billingMinor, billingCurrency, fx, AuthorizationState.DECLINED, reason,
                null, authorizedAt);
    }

    /**
     * The money moved (task 05.6). {@code settledMinor} is what the merchant actually took, in the
     * billing currency, and is the number the feed shows from now on - equal to
     * {@link #getBillingMinor()} on an exact settlement, under it on a partial, over it on a tip.
     *
     * <p>The state is derived here rather than passed in, because it is a statement about the numbers
     * and there is no version of it a caller should be able to disagree with: anything short of the
     * full hold is {@link AuthorizationState#PARTIALLY_SETTLED}, and an over-settlement is
     * {@link AuthorizationState#SETTLED} - the authorization was settled in full and then some, which
     * {@code excessMinor} is what records.
     *
     * @param excessMinor how much was taken beyond the hold; zero in the ordinary case, and never
     *                    null once a settlement has happened - see the migration on why zero and null
     *                    are different facts here
     */
    public void markSettled(long settledMinor, long excessMinor, UUID ledgerEntryId, UUID excessLedgerEntryId,
            Instant settledAt) {
        if (state != AuthorizationState.APPROVED) {
            throw new IllegalStateException("cannot settle authorization " + id + " from state " + state);
        }
        this.settledMinor = settledMinor;
        this.excessMinor = excessMinor;
        this.ledgerEntryId = ledgerEntryId;
        this.excessLedgerEntryId = excessLedgerEntryId;
        this.settledAt = settledAt;
        this.state = billingMinor != null && settledMinor < billingMinor
                ? AuthorizationState.PARTIALLY_SETTLED
                : AuthorizationState.SETTLED;
    }

    /**
     * The merchant will not be claiming the money: the hold is released and nothing was ever posted.
     *
     * <p>Note what this does <em>not</em> set - no {@code ledgerEntryId}, no {@code settledMinor}, no
     * {@code settledAt}. A reversal is the absence of an accounting event, and the database says so
     * too: {@code card_authorization_unsettled_has_no_posting} rejects a non-settled row that carries
     * any of them.
     */
    public void markReversed() {
        if (state != AuthorizationState.APPROVED) {
            throw new IllegalStateException("cannot reverse authorization " + id + " from state " + state);
        }
        this.state = AuthorizationState.REVERSED;
    }

    @Override
    @Transient
    public UUID getId() {
        return id;
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

    public UUID getCardId() {
        return cardId;
    }

    public String getNetworkRef() {
        return networkRef;
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

    public long getAmountMinor() {
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

    public AuthorizationState getState() {
        return state;
    }

    public DeclineReason getDeclineReason() {
        return declineReason;
    }

    public String getFxMidRate() {
        return fxMidRate;
    }

    public String getFxExchangeRate() {
        return fxExchangeRate;
    }

    /** fx-service's exchange spread. Null when no conversion happened. */
    public Integer getFxSpreadBps() {
        return fxSpreadBps;
    }

    /** card-service's mark-up, deliberately a separate figure from {@link #getFxSpreadBps()}. */
    public Integer getFxMarkupBps() {
        return fxMarkupBps;
    }

    /**
     * The mark-up in minor units of {@link #getBillingCurrency()} - the slice of
     * {@link #getBillingMinor()} bound for {@code FEE_INCOME} when this authorization settles. Null
     * when no conversion happened, which is not the same fact as zero.
     */
    public Long getFxMarkupMinor() {
        return fxMarkupMinor;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public UUID getLedgerEntryId() {
        return ledgerEntryId;
    }

    /** What the merchant actually took, in the billing currency. Null until a settlement arrives. */
    public Long getSettledMinor() {
        return settledMinor;
    }

    /**
     * How much of {@link #getSettledMinor()} went beyond the hold - the over-settlement flag.
     * Zero on an ordinary settlement, null before one.
     */
    public Long getExcessMinor() {
        return excessMinor;
    }

    public UUID getExcessLedgerEntryId() {
        return excessLedgerEntryId;
    }

    public Instant getAuthorizedAt() {
        return authorizedAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }
}
