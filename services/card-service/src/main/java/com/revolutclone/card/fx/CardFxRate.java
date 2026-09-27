package com.revolutclone.card.fx;

/**
 * The rate fx-service gave for one foreign-currency authorization, in the direction the holder
 * sells: how many units of {@code merchantCurrency} one unit of {@code walletCurrency} buys.
 *
 * <p>Both rates are kept, not just the one used. {@code exchangeRate} is {@code midRate} less
 * fx-service's own spread and is what the conversion is done at; {@code midRate} and
 * {@code spreadBps} are what make the exchange spread a separate, auditable figure from the card
 * mark-up that {@link CardFxPricer} adds afterwards. Both end up on the {@code card_authorization}
 * row, which is how "why was I charged this?" is answerable days later from one row.
 *
 * <p>Rates are exact decimal text throughout. {@code FxMath} is the only type in this project that
 * names an arbitrary-precision decimal at all - CLAUDE.md hard rule 1 - so a rate crossing a service
 * boundary crosses it as text.
 */
public record CardFxRate(
        String walletCurrency,
        String merchantCurrency,
        String midRate,
        String exchangeRate,
        int spreadBps) {
}
