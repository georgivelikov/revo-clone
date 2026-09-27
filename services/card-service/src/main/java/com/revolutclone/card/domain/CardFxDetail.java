package com.revolutclone.card.domain;

/**
 * The FX facts a foreign-currency authorization records on its own row: the two rates, the two
 * basis-point figures they belong to, and the mark-up in minor units of the wallet's currency.
 *
 * <p>A parameter carrier, not an {@code @Embeddable} - the five columns are plain nullable columns
 * on {@link CardAuthorization}, and this exists so that adding them did not turn a twelve-argument
 * factory method into a seventeen-argument one.
 *
 * <p><strong>Why the rate is stored at all, given the amount already is.</strong>
 * plans/phase-05-cards.md's "Holds and the FX rate" gotcha asks for an explicit choice between
 * holding at the auth rate and re-rating at settlement. The choice here is <strong>hold at the auth
 * rate and settle at the auth rate</strong> - what most issuers do for the held amount, and the only
 * option under which the amount the holder saw on the push notification is the amount that
 * eventually leaves their wallet. Settlement therefore never needs a rate, and these columns are
 * evidence rather than input: they are what answers "why EUR 40.60?" days later, and what would let
 * a re-rating scheme be introduced later without losing the original basis. {@code fxSpreadBps} and
 * {@code fxMarkupBps} sit side by side for the same reason - one row shows the two charges apart.
 *
 * @param midRate      fx-service's mid at decision time, in merchant units per unit of wallet
 *                     currency
 * @param exchangeRate the same rate less fx-service's exchange spread; the rate the conversion used
 * @param spreadBps    the exchange spread, fx-service's revenue
 * @param markupBps    the card mark-up, card-service's own charge
 * @param markupMinor  that mark-up in whole minor units of the wallet's currency - the part of the
 *                     billing amount bound for {@code FEE_INCOME} when this authorization settles
 */
public record CardFxDetail(
        String midRate,
        String exchangeRate,
        int spreadBps,
        int markupBps,
        long markupMinor) {
}
