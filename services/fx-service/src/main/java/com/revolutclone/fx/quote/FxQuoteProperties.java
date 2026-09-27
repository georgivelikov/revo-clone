package com.revolutclone.fx.quote;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two numbers the quoting path is allowed to have an opinion about.
 *
 * <p>{@code spreadBps} defaults to the 50 basis points plans/phase-04-fx-multicurrency.md names, and
 * is configurable for the reason the phase gives: "make it configurable so you can demo the revenue
 * line". Turning it up makes {@code FEE_INCOME} move visibly; turning it to zero is a legitimate
 * setting, and the FX posting then has no fee leg amount to speak of rather than a broken one.
 *
 * <p>{@code ttl} is the 30 seconds a quote stays firm, and is also the expiry handed to the ledger for
 * the hold - one value, so the quote and the funds behind it can never outlive each other.
 */
@ConfigurationProperties(prefix = "revolut.fx.quote")
public record FxQuoteProperties(int spreadBps, Duration ttl) {

    public FxQuoteProperties {
        if (spreadBps < 0 || spreadBps >= 10_000) {
            throw new IllegalArgumentException("revolut.fx.quote.spread-bps must be in [0, 10000), got " + spreadBps);
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("revolut.fx.quote.ttl must be positive, got " + ttl);
        }
    }
}
