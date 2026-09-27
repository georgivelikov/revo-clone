package com.revolutclone.card.fx;

import com.revolutclone.card.domain.CardFxDetail;
import com.revolutclone.money.Money;
import java.util.Objects;

/**
 * What one tap costs the holder, and how that figure is made up.
 *
 * <pre>
 *   merchantAmount   USD 50.00   &lt;- what the merchant asked for; never held, never posted
 *   ----------------------------
 *   exchanged        EUR 40.20   &lt;- converted at fx-service's rate (mid less its spread)
 *   markUp           EUR  0.40   &lt;- the card mark-up: markUpBps of the line above
 *   ============================
 *   billing          EUR 40.60   &lt;- what is held, and what limits are measured against
 * </pre>
 *
 * <p><strong>{@code exchanged + markUp == billing} exactly, always.</strong> Not approximately, and
 * not after a rounding adjustment: the mark-up is a basis-point charge on the already-converted
 * whole minor units ({@code FxMath.bpsOf}), so the split is integer addition. That matters because
 * the settlement entry credits the two halves to two different accounts against one debit of the
 * billing amount, and the ledger's per-currency zero-sum invariant would notice a sub-unit
 * discrepancy. Deriving the mark-up as the gap between two independently rounded conversions would
 * have produced exactly that.
 *
 * <p><strong>A domestic purchase is the same type with nothing in it.</strong>
 * {@link #domestic(Money)} makes the merchant amount the billing amount, the mark-up zero and the
 * exchange the whole of it, so the decision chain has one path and not two. {@link #isForeign()} is
 * how the {@code card_authorization} row knows to leave its FX columns null.
 */
public record CardFxPricing(
        Money merchantAmount,
        Money exchanged,
        Money markUp,
        Money billing,
        String midRate,
        String exchangeRate,
        int spreadBps,
        int markUpBps) {

    public CardFxPricing {
        Objects.requireNonNull(merchantAmount, "merchantAmount");
        Objects.requireNonNull(exchanged, "exchanged");
        Objects.requireNonNull(markUp, "markUp");
        Objects.requireNonNull(billing, "billing");
        // The invariant the settlement entry depends on, asserted where it is established rather
        // than discovered days later by a journal entry that will not balance.
        if (!exchanged.plus(markUp).equals(billing)) {
            throw new IllegalArgumentException("billing " + billing + " is not " + exchanged
                    + " plus a mark-up of " + markUp);
        }
    }

    /**
     * A merchant already charging in the wallet's currency: no rate was needed, no mark-up applies,
     * and the wallet is charged precisely what was asked for.
     */
    public static CardFxPricing domestic(Money amount) {
        return new CardFxPricing(amount, amount, Money.of(0L, amount.currency().getCurrencyCode()),
                amount, null, null, 0, 0);
    }

    /** True when a conversion happened - which is the only case with a rate and a mark-up to record. */
    public boolean isForeign() {
        return midRate != null;
    }

    /**
     * The part of the billing amount bound for one ledger account at settlement. The two
     * destinations sum to {@link #billing()} by construction; that is what
     * {@link CardBillingDestination} exists to say out loud.
     */
    public Money amountFor(CardBillingDestination destination) {
        return switch (destination) {
            case CARD_SETTLEMENT -> exchanged;
            case FEE_INCOME -> markUp;
        };
    }

    /**
     * The columns this pricing contributes to the {@code card_authorization} row, or {@code null}
     * for a domestic purchase which contributes none.
     */
    public CardFxDetail fxDetail() {
        return isForeign()
                ? new CardFxDetail(midRate, exchangeRate, spreadBps, markUpBps, markUp.minor())
                : null;
    }
}
