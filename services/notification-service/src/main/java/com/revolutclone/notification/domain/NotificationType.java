package com.revolutclone.notification.domain;

/**
 * The kinds of notification this service keeps. Exactly the four {@code V3__notification.sql}
 * names on {@code notification.type} - the persisted set is deliberately smaller than the set of
 * frames that go down the socket, because a row here is "something the user would still want to
 * see tomorrow" while a payment moving from PENDING to COMPLETED is a status transition the UI
 * reconciles against and then forgets.
 */
public enum NotificationType {

    /** Money arrived: a CREDIT posting on one of this user's wallets. */
    MONEY_IN,

    /** Money left: a DEBIT posting on one of this user's wallets. */
    MONEY_OUT,

    /** The ledger rejected a transfer this user initiated, with a reason worth keeping. */
    TRANSFER_FAILED,

    /** Someone else decided what to say - a {@code NotificationPushV1} passed through verbatim. */
    PUSH
}
