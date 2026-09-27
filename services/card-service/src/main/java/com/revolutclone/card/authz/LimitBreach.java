package com.revolutclone.card.authz;

import com.revolutclone.card.domain.LimitPeriod;
import com.revolutclone.money.Money;
import java.time.Duration;

/**
 * Why step 5 said no: which ceiling was hit, what had already been spent against it, and what the tap
 * would have taken it to.
 *
 * <p>The published decline carries only {@code LIMIT_EXCEEDED} - the network is told that the issuer
 * refused, and the amounts behind the refusal are the holder's business, not the merchant's. This record
 * exists so the refusal is still explainable on our side of the line: an operator asked "why was this
 * card declined at 14:02?" needs the window, the ceiling and the running total, and reconstructing them
 * from a bare reason code afterwards means re-running the evaluation against data that has since moved.
 *
 * @param period       the ceiling that was hit
 * @param window       how far back the running total was measured; {@link Duration#ZERO} for
 *                     {@link LimitPeriod#PER_TRANSACTION}, which measures nothing
 * @param limit        the ceiling itself
 * @param alreadySpent what the window already held, before this tap
 * @param requested    the billing amount of this tap - the wallet's side, not the merchant's
 */
public record LimitBreach(LimitPeriod period, Duration window, Money limit, Money alreadySpent, Money requested) {

    /** The line an operator reads in the log. Never carries the PAN - it names no card number at all. */
    public String describe() {
        if (period == LimitPeriod.PER_TRANSACTION) {
            return "%s limit of %s exceeded by a single %s".formatted(period, limit, requested);
        }
        return "%s limit of %s exceeded: %s already spent in the last %s, plus %s requested"
                .formatted(period, limit, alreadySpent, window, requested);
    }
}
