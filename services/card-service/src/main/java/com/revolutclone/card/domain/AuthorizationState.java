package com.revolutclone.card.domain;

/**
 * The {@code card_authorization.state} vocabulary from plans/phase-05-cards.md's schema comment.
 *
 * <p>Only {@link #APPROVED} and {@link #DECLINED} are reachable from the authorization chain (task
 * 05.3). The rest are the settlement lifecycle - {@link #SETTLED}/{@link #PARTIALLY_SETTLED} from a
 * capture, {@link #REVERSED} from a reversal, {@link #EXPIRED} from the seven-day sweep - and are
 * declared here because they are the same column's values, not because this task writes them.
 */
public enum AuthorizationState {
    APPROVED,
    DECLINED,
    SETTLED,
    PARTIALLY_SETTLED,
    REVERSED,
    EXPIRED
}
