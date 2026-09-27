package com.revolutclone.payment.fx;

/**
 * payment-service's port onto fx-service's quoting API - a port, like fx-service's own
 * {@code LedgerReservations}, so a test can substitute a fake without the real HTTP hop
 * (testcontainers-kafka cannot start on this machine; see docs/spring-boot-4-notes.md, and this
 * is the same reasoning fx-service's own tests already lean on).
 *
 * <p>The one synchronous, sideways HTTP call plans/phase-04-fx-multicurrency.md's Variant section
 * names deliberately: "the user must see a firm rate before confirming." Everything after this
 * call succeeds runs through the outbox, exactly like a same-currency transfer.
 */
public interface FxQuotes {

    /**
     * @throws FxQuoteUnavailableException if fx-service refused the quote, or could not be
     *         reached - the caller's only correct response either way is to reject the transfer
     */
    FxQuoteHold quote(FxQuoteCommand command);
}
