package com.revolutclone.card.authz;

import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.CardAuthorizationRepository;
import com.revolutclone.card.domain.CardLimit;
import com.revolutclone.card.domain.CardLimitRepository;
import com.revolutclone.card.domain.LimitPeriod;
import com.revolutclone.money.CurrencyMismatchException;
import com.revolutclone.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Step 5 of plans/phase-05-cards.md's authorization flow: "limits: per-transaction, rolling daily,
 * rolling monthly -> exceeded -> DECLINED(LIMIT_EXCEEDED)".
 *
 * <p><strong>The window is computed, never stored.</strong> There is no running-total column and no
 * counter to increment, because a counter is a second copy of a fact {@code card_authorization} already
 * records, and the two would drift the first time a settlement, reversal or expiry changed a row's state
 * without anybody remembering to adjust the counter. The cost is a {@code sum} per decision, which the
 * {@code (card_id, authorized_at desc)} index in the schema exists to serve - the same index the
 * per-card spend feed reads.
 *
 * <p><strong>What counts as spend is a state question, not an amount question.</strong>
 * {@link #COUNTS_AS_SPEND} is the states in which the money is either held or gone:
 * {@code APPROVED} (held, not yet moved), {@code SETTLED} and {@code PARTIALLY_SETTLED} (moved). The
 * three it omits are the point:
 *
 * <ul>
 *   <li>{@code DECLINED} - nothing was ever held. A refused purchase that consumed limit would let a
 *       holder be locked out by taps that never succeeded.</li>
 *   <li>{@code REVERSED} - the hold was released and no posting was ever made, so the money came back;
 *       the limit has to come back with it.</li>
 *   <li>{@code EXPIRED} - the seven-day sweep released the hold for the same reason.</li>
 * </ul>
 *
 * <p>Settlement is what makes the omissions matter: task 05.6 is what first writes those states, and an
 * evaluator that counted them would silently shrink a holder's limit every time a merchant abandoned an
 * authorization. This is also why a partial settlement still counts its full {@code billingMinor} until
 * that task decides otherwise - the billing amount on the row is the authoritative figure for the window,
 * whatever later happened to the hold behind it.
 *
 * <p><strong>Ordering is deliberate: narrowest ceiling first.</strong> {@link LimitPeriod} declares
 * per-transaction, then daily, then monthly, and the first breach wins. A holder told "that one purchase
 * is too large" learns something they can act on; the same purchase reported as a monthly-limit breach
 * would send them looking at the wrong thing.
 */
@Component
public class CardLimitEvaluator {

    /**
     * The states in which a card's own spend has actually consumed something. See the class javadoc for
     * why the other three are absent - that omission is the substance of this class, not an oversight.
     */
    private static final Set<AuthorizationState> COUNTS_AS_SPEND = Set.of(
            AuthorizationState.APPROVED,
            AuthorizationState.SETTLED,
            AuthorizationState.PARTIALLY_SETTLED);

    private final CardLimitRepository limits;
    private final CardAuthorizationRepository authorizations;
    private final CardLimitProperties properties;

    public CardLimitEvaluator(CardLimitRepository limits, CardAuthorizationRepository authorizations,
            CardLimitProperties properties) {
        this.limits = limits;
        this.authorizations = authorizations;
        this.properties = properties;
    }

    /**
     * Evaluates every ceiling configured on the card against one proposed tap.
     *
     * @param cardId    the card being tapped
     * @param billing   what the wallet would be charged - the billing amount from step 4, in the
     *                  wallet's currency, because that is the currency a limit is denominated in
     * @param decidedAt the decision instant, which is where both rolling windows are measured back from.
     *                  Supplied rather than read from a clock here so that one decision evaluates every
     *                  window against the same instant
     * @return the ceiling that was hit, or empty if the tap is within all of them - including the case
     *         where the card has no limits at all, which is not a limit of zero
     * @throws CurrencyMismatchException if a limit is denominated in something other than the wallet's
     *         currency. Deliberately thrown rather than turned into a decline: a misconfigured limit is
     *         our bug, and refusing a good card because of it would hide the bug behind a holder's
     *         inconvenience. Thrown from {@link Money}, which is the whole reason limits are compared as
     *         money and not as longs
     */
    public Optional<LimitBreach> evaluate(UUID cardId, Money billing, Instant decidedAt) {
        List<CardLimit> configured = limits.findByCardId(cardId).stream()
                .sorted(Comparator.comparing(CardLimit::getPeriod))
                .toList();

        for (CardLimit limit : configured) {
            Optional<LimitBreach> breach = evaluate(limit, cardId, billing, decidedAt);
            if (breach.isPresent()) {
                return breach;
            }
        }
        return Optional.empty();
    }

    private Optional<LimitBreach> evaluate(CardLimit limit, UUID cardId, Money billing, Instant decidedAt) {
        Duration window = limit.getPeriod() == LimitPeriod.PER_TRANSACTION
                ? Duration.ZERO
                : properties.windowFor(limit.getPeriod());

        Money alreadySpent = limit.getPeriod() == LimitPeriod.PER_TRANSACTION
                ? Money.of(0, billing.currency().getCurrencyCode())
                : spentSince(cardId, billing, decidedAt.minus(window));

        // plus() is safe by construction - alreadySpent is built in billing's currency - but compareTo
        // crosses into the limit's, which is exactly where a limit stored in the wrong currency has to
        // stop being silently comparable.
        Money afterThisTap = alreadySpent.plus(billing);
        if (afterThisTap.compareTo(limit.amount()) > 0) {
            return Optional.of(new LimitBreach(
                    limit.getPeriod(), window, limit.amount(), alreadySpent, billing));
        }
        return Optional.empty();
    }

    /**
     * The rolling total. Reads committed rows every time rather than caching anything: two taps on one
     * card are serialized by {@link PerCardDecisionLock}, and the second one must see the first one's
     * row or the window is decorative.
     */
    private Money spentSince(UUID cardId, Money billing, Instant windowOpenedAt) {
        long minor = authorizations.sumBillingMinorSince(
                cardId, billing.currency().getCurrencyCode(), COUNTS_AS_SPEND, windowOpenedAt);
        return Money.of(minor, billing.currency().getCurrencyCode());
    }
}
