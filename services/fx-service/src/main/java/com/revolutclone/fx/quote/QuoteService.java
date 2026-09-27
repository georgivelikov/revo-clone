package com.revolutclone.fx.quote;

import com.revolutclone.fx.ledger.HeldFunds;
import com.revolutclone.fx.ledger.HoldFundsCommand;
import com.revolutclone.fx.ledger.LedgerReservations;
import com.revolutclone.fx.quote.error.InvalidQuoteRequestException;
import com.revolutclone.fx.quote.error.SellWalletNotFoundException;
import com.revolutclone.fx.quote.error.UnknownCurrencyPairException;
import com.revolutclone.fx.rate.FxRateCache;
import com.revolutclone.fx.readmodel.WalletResolver;
import com.revolutclone.money.FxMath;
import com.revolutclone.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * plans/phase-04-fx-multicurrency.md's five quoting steps, in order: look up the mid, apply the
 * spread, convert with {@code HALF_EVEN} to the target currency's digits, <strong>reserve</strong> the
 * sell amount on the caller's wallet for 30 seconds, persist {@code ACTIVE}.
 *
 * <p><strong>The order of steps 4 and 5 is the interesting part.</strong> Reserving before persisting
 * is what makes the phase's third compensation row true: "fx-service crashes between reserve and
 * persist - the reservation's own 30 s expiry releases it - the ledger's timeout is the backstop".
 * Persisting first and reserving afterwards would instead leave an {@code ACTIVE} quote with no funds
 * behind it, executable against a balance that was never checked. The cost is that a crash in the gap
 * strands a hold for up to the quote TTL, which is precisely the trade the phase asks for: "design
 * every hold with a timeout owned by the resource, not by the orchestrator".
 *
 * <p>For the same reason there is no {@code @Transactional} spanning this method. The hold is an HTTP
 * call to another service and cannot be rolled back by a database transaction; pretending otherwise by
 * wrapping both would only widen the window in which a commit fails after the funds are already held.
 *
 * <p><strong>Reserving rather than only locking a rate</strong> is also what makes quoting refuse the
 * second of two quotes taken against one balance - {@code QuoteReservesFundsTest}. The available-funds
 * check itself is the ledger's, not this service's: fx-service holds no balances and must not, so it
 * asks and propagates the answer.
 */
@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    /**
     * The ledger's key space is global while a client's {@code Idempotency-Key} is unique only to that
     * client, so the hold is keyed on the quote instead - the same rule payment-service applies to its
     * own ledger key.
     */
    private static final String HOLD_KEY_PREFIX = "fx-quote:";

    private final FxRateCache rates;
    private final WalletResolver wallets;
    private final LedgerReservations ledger;
    private final FxQuoteRepository quotes;
    private final FxQuoteProperties properties;
    private final Clock clock;

    public QuoteService(FxRateCache rates, WalletResolver wallets, LedgerReservations ledger,
            FxQuoteRepository quotes, FxQuoteProperties properties, Clock clock) {
        this.rates = rates;
        this.wallets = wallets;
        this.ledger = ledger;
        this.quotes = quotes;
        this.properties = properties;
        this.clock = clock;
    }

    public FxQuote quote(CreateQuoteCommand command) {
        // A redelivered request must not reserve a second time against the same balance - that would
        // turn a retry into a decline, which is exactly what an idempotency key exists to prevent.
        var alreadyQuoted = quotes.findByIdempotencyKey(command.idempotencyKey());
        if (alreadyQuoted.isPresent()) {
            return alreadyQuoted.get();
        }

        String sellCurrency = validCurrency(command.sellCurrency());
        String buyCurrency = validCurrency(command.buyCurrency());
        if (sellCurrency.equals(buyCurrency)) {
            throw new InvalidQuoteRequestException("sellCurrency and buyCurrency are both " + sellCurrency);
        }

        String midRate = rates.mid(sellCurrency, buyCurrency)
                .orElseThrow(() -> new UnknownCurrencyPairException(sellCurrency, buyCurrency));
        int spreadBps = properties.spreadBps();
        String clientRate = FxMath.applySpread(midRate, spreadBps);

        long sellMinor = sellAmountOf(command, sellCurrency, buyCurrency, clientRate);
        // Always re-derive the buy amount from the sell amount at the client rate, even when the
        // client asked for a buy amount. The two sides of a quote have to be tied together by one
        // rate: deriving the sell amount from a requested buy amount and then posting both as given
        // would leave the pair inconsistent by up to a minor unit, and the FX entry's fee leg would
        // silently absorb the difference. The client gets within one minor unit of what they asked
        // for, and the quote they are shown is the one that will be posted.
        FxLegs legs = FxLegs.of(sellMinor, sellCurrency, buyCurrency, midRate, clientRate);

        UUID quoteId = UUID.randomUUID();
        // Truncated to what timestamptz actually stores. The same expiry is written on the quote row and
        // handed to the ledger for the hold, and the ledger is told first: taken at nanosecond precision,
        // the hold would expire fractionally after the row that records it, and the two systems would
        // disagree about the one instant that decides whether an execute is still valid.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = now.plus(properties.ttl());
        UUID sellLedgerAccountId = wallets.resolveWalletLedgerAccount(command.userId(), sellCurrency)
                .orElseThrow(() -> new SellWalletNotFoundException(sellCurrency));

        HeldFunds held = ledger.hold(new HoldFundsCommand(sellLedgerAccountId, sellMinor, sellCurrency,
                quoteId, HOLD_KEY_PREFIX + quoteId, expiresAt));

        FxQuote quote = new FxQuote(quoteId, command.idempotencyKey(), command.userId(), sellCurrency,
                buyCurrency, sellMinor, legs.clientBuyMinor(), midRate, clientRate, spreadBps,
                held.id(), FxQuoteState.ACTIVE, expiresAt, now);
        try {
            return quotes.insert(quote);
        } catch (DuplicateKeyException ex) {
            // Two requests with one key raced past the read above. The loser's hold is left for the
            // ledger's own expiry to release rather than released here: this service has no evidence
            // which of the two holds the winning quote actually points at, and releasing the wrong one
            // would unfund a live quote. Bounded by the quote TTL, and the funds come back either way.
            log.warn("idempotency key {} was used concurrently; returning the quote that won and "
                    + "leaving reservation {} to expire", command.idempotencyKey(), held.id());
            return quotes.findByIdempotencyKey(command.idempotencyKey()).orElseThrow(() -> ex);
        }
    }

    /**
     * The sell amount, whichever side of the exchange the client filled in. A requested buy amount is
     * turned into a sell amount at the inverse of the <em>client</em> rate, so the spread is applied
     * once and in the same direction either way round - plans/phase-04-fx-multicurrency.md's
     * inverse-pair gotcha.
     */
    private long sellAmountOf(CreateQuoteCommand command, String sellCurrency, String buyCurrency,
            String clientRate) {
        boolean hasSell = command.sellMinor() != null;
        boolean hasBuy = command.buyMinor() != null;
        if (hasSell == hasBuy) {
            throw new InvalidQuoteRequestException(
                    "specify exactly one of sellMinor or buyMinor, not " + (hasSell ? "both" : "neither"));
        }

        long requested = hasSell ? command.sellMinor() : command.buyMinor();
        if (requested <= 0) {
            throw new InvalidQuoteRequestException("amount must be greater than zero, got " + requested);
        }
        if (hasSell) {
            return requested;
        }

        long sellMinor = FxMath.convertWithResidual(Money.of(requested, buyCurrency),
                        FxMath.invert(clientRate), sellCurrency)
                .converted()
                .minor();
        if (sellMinor <= 0) {
            throw new InvalidQuoteRequestException("buyMinor " + requested + " " + buyCurrency
                    + " is worth less than one minor unit of " + sellCurrency);
        }
        return sellMinor;
    }

    private static String validCurrency(String code) {
        if (code == null || code.isBlank()) {
            throw new InvalidQuoteRequestException("currency is required");
        }
        String upper = code.strip().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(upper);
        } catch (IllegalArgumentException ex) {
            throw new InvalidQuoteRequestException("unknown ISO currency code " + code);
        }
        return upper;
    }
}
