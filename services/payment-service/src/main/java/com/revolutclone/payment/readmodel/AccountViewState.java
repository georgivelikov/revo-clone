package com.revolutclone.payment.readmodel;

/**
 * Mirrors {@code account-service}'s own {@code AccountState}. Deliberately a copy rather than a
 * shared type: the two services are only coupled through the published event contract, and a
 * shared enum would turn every state account-service invents into a compile-time dependency
 * here. The names must agree with the strings account-service publishes, and
 * {@code AccountOpenedEventHandler} is the one place that maps them.
 *
 * <p>Only {@link #ACTIVE} is reachable today - {@code AccountOpenedV1} is the only event on
 * {@code account.account.v1}, and an account is ACTIVE the moment it is opened. The other two
 * exist because {@code POST /api/payments} validates "both accounts exist and are ACTIVE", and
 * that check should read as a state comparison rather than as an always-true assertion the day
 * a freeze event is added.
 */
public enum AccountViewState {
    ACTIVE,
    FROZEN,
    CLOSED
}
