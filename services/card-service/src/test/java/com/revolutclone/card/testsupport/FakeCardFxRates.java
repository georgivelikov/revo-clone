package com.revolutclone.card.testsupport;

import com.revolutclone.card.fx.CardFxRate;
import com.revolutclone.card.fx.CardFxRateUnavailableException;
import com.revolutclone.card.fx.CardFxRates;
import com.revolutclone.money.FxMath;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * fx-service's rate endpoint, standing in for the real one.
 *
 * <p><strong>It applies the spread rather than being told the answer.</strong> A double that simply
 * returned whatever rate a test handed it would let card-service's arithmetic be wrong in exactly the
 * direction that matters - handing the exchange spread to the holder instead of the house - and no
 * test would notice. This holds a <em>mid</em> and derives the exchange rate the same way
 * {@code InternalFxRateController} does, with {@code FxMath.applySpread} in the direction the wallet
 * sells, so a test states a mid and a spread and the numbers that follow are the real ones.
 *
 * <p>Unknown pair behaves as fx-service's still-warming cache does: a failure, which the chain must
 * turn into a retry and never into a decline.
 */
public class FakeCardFxRates implements CardFxRates {

    /** fx-service's own default, so the derived rates match what a running stack would produce. */
    public static final int DEFAULT_SPREAD_BPS = 50;

    private final Map<String, String> midsByPair = new ConcurrentHashMap<>();
    private volatile int spreadBps = DEFAULT_SPREAD_BPS;

    /**
     * @param mid how many units of {@code merchantCurrency} one unit of {@code walletCurrency} buys -
     *            the direction fx-service is asked in, and the direction the spread is applied in
     */
    public void setMid(String walletCurrency, String merchantCurrency, String mid) {
        midsByPair.put(pair(walletCurrency, merchantCurrency), mid);
    }

    public void setSpreadBps(int spreadBps) {
        this.spreadBps = spreadBps;
    }

    public void reset() {
        midsByPair.clear();
        spreadBps = DEFAULT_SPREAD_BPS;
    }

    @Override
    public CardFxRate rate(String walletCurrency, String merchantCurrency) {
        String mid = midsByPair.get(pair(walletCurrency, merchantCurrency));
        if (mid == null) {
            throw new CardFxRateUnavailableException(
                    "no mid rate seeded for " + pair(walletCurrency, merchantCurrency));
        }
        return new CardFxRate(walletCurrency, merchantCurrency, mid,
                FxMath.applySpread(mid, spreadBps), spreadBps);
    }

    private static String pair(String walletCurrency, String merchantCurrency) {
        return walletCurrency.toUpperCase(Locale.ROOT) + "/" + merchantCurrency.toUpperCase(Locale.ROOT);
    }
}
