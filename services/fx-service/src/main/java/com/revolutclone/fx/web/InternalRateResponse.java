package com.revolutclone.fx.web;

/**
 * One rate, in the direction it was asked for: how many units of {@code buyCurrency} a unit of
 * {@code sellCurrency} buys.
 *
 * <p>Carries the mid <em>and</em> the client rate, plus the basis points between them, rather than
 * only the number the caller should use. A caller that received a single rate could not tell the
 * exchange spread apart from anything it went on to add itself - which is exactly the distinction
 * {@code card-service} has to keep (plans/phase-05-cards.md task 5: "the card mark-up applied
 * separately from the exchange spread"). Sending all three makes the split a fact on the wire
 * instead of a convention.
 *
 * <p>Rates are exact decimal text, never a floating-point number and never an arbitrary-precision
 * decimal type on a DTO - the same representation {@code FxMath} and the {@code numeric(20,10)} rate
 * columns use. {@code FxMath} is the only type allowed to name that arithmetic; see CLAUDE.md hard
 * rule 1.
 */
public record InternalRateResponse(
        String sellCurrency,
        String buyCurrency,
        String midRate,
        String clientRate,
        int spreadBps) {
}
