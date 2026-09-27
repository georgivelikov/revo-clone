package com.revolutclone.card.domain;

/**
 * The {@code card_limit.period} vocabulary from plans/phase-05-cards.md's schema.
 *
 * <p>Declared in the order step 5 of the authorization flow names them - "limits: per-transaction,
 * rolling daily, rolling monthly" - because {@link CardLimit#getPeriod()} is what
 * {@code CardLimitEvaluator} sorts on, and the order a decline is attributed to matters to the holder:
 * "this one purchase is too large" and "you have spent too much today" are different things to be told,
 * and the narrower window is the more useful answer.
 *
 * <p>{@link #PER_TRANSACTION} is not a window at all - it is a ceiling on a single amount, so no history
 * is read for it. The other two are <em>rolling</em> windows measured back from the decision instant;
 * see {@code CardLimitProperties} for why rolling and not calendar.
 */
public enum LimitPeriod {
    PER_TRANSACTION,
    DAILY,
    MONTHLY
}
