package com.revolutclone.card.domain;

/**
 * Why an authorization was refused - the reasons plans/phase-05-cards.md's flow names, in the order
 * its steps can produce them.
 *
 * <p>{@link #LIMIT_EXCEEDED} is step 5's reason and is unreachable until task 05.4 adds the rolling
 * window over {@code card_authorization}. It is declared now rather than then because it is part of
 * the decision's vocabulary, not of the evaluator: the chain's shape is decided here, and a later
 * task filling in one step should not have to widen the contract to do it.
 *
 * <p>{@link #CARD_CANCELLED} is the one addition to the flow diagram's list. The diagram names the
 * frozen case because that is the one the UI drives, but a cancelled card is a different fact, and
 * telling the holder their card is frozen would invite them to unfreeze something that can never be
 * unfrozen.
 */
public enum DeclineReason {
    CARD_NOT_FOUND,
    CARD_FROZEN,
    CARD_CANCELLED,
    CARD_EXPIRED,
    LIMIT_EXCEEDED,
    INSUFFICIENT_FUNDS
}
