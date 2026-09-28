package com.revolutclone.card.settlement;

import com.revolutclone.card.domain.AuthorizationState;

/**
 * What a settlement or reversal message did to an authorization.
 *
 * <p>An {@link #ignored} outcome is not a failure - it is the honest answer to a message there is
 * nothing to do about, and the reason it is a returned value rather than a thrown exception. A
 * settlement quoting a {@code networkRef} we never authorized, or one quoting an authorization that
 * has already reached a terminal state, cannot be made to succeed by retrying: the message commits,
 * with a log line, instead of cycling through the retry chain to a dead-letter topic. This is the
 * "business rejections must commit, not throw" rule phase 07 audits every consumer for, applied at
 * the point the rule is easiest to get wrong.
 *
 * @param networkRef the network's reference, which is what every card message is matched on
 * @param state      the authorization's state afterwards, or null when nothing was applied
 * @param reason     why nothing was applied; null on an applied outcome
 */
public record SettlementOutcome(String networkRef, AuthorizationState state, String reason) {

    static SettlementOutcome applied(String networkRef, AuthorizationState state) {
        return new SettlementOutcome(networkRef, state, null);
    }

    static SettlementOutcome ignored(String networkRef, String reason) {
        return new SettlementOutcome(networkRef, null, reason);
    }

    public boolean isApplied() {
        return state != null;
    }

    public String describe() {
        return isApplied() ? state.name() : "IGNORED (" + reason + ")";
    }
}
