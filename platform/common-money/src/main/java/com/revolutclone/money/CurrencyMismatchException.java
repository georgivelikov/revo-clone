package com.revolutclone.money;

import java.io.Serial;
import java.util.Currency;
import java.util.Objects;

/**
 * Thrown when a binary operation is asked to combine two amounts in different currencies.
 *
 * <p>There is no implicit conversion anywhere in this project. Crossing currencies is an
 * explicit FX operation with a rate, an audit trail and a counterparty; anything that
 * quietly added EUR to USD would be inventing an exchange rate of 1.0.
 *
 * <p>Unchecked, because business code is never expected to recover from it — a mismatch is
 * a programming error, not a runtime condition.
 */
public class CurrencyMismatchException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Currency left;
    private final transient Currency right;

    public CurrencyMismatchException(Currency left, Currency right) {
        super("cannot combine %s with %s: no implicit currency conversion"
                .formatted(Objects.requireNonNull(left, "left").getCurrencyCode(),
                           Objects.requireNonNull(right, "right").getCurrencyCode()));
        this.left = left;
        this.right = right;
    }

    /** The currency of the left-hand operand. */
    public Currency left() {
        return left;
    }

    /** The currency of the right-hand operand. */
    public Currency right() {
        return right;
    }
}
