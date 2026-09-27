package com.revolutclone.fx.quote;

/**
 * plans/phase-04-fx-multicurrency.md's {@code fx_quote.state} column comment:
 * {@code ACTIVE | CONSUMED | EXPIRED | CANCELLED}.
 *
 * <p>Only {@link #ACTIVE} is reachable from task 04.4 - a quote is born active with its funds held.
 * The other three are the ways it ends: executed (04.5), swept after its 30 seconds ran out (04.6),
 * or compensated because the ledger refused the capture (04.5's compensation path).
 */
public enum FxQuoteState {

    /** Funds are held and the rate is firm until {@code expiresAt}. */
    ACTIVE,

    /** Executed: the reservation was captured and the FX entry posted. */
    CONSUMED,

    /** Nobody executed it in time; the sweep released its reservation. */
    EXPIRED,

    /** Execution failed after the hold - the ledger rejected the capture. */
    CANCELLED
}
