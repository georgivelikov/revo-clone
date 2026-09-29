-- Task 06.1's own tables: the durable record of what this service's two consumers
-- (LedgerEntryKafkaListener, CardAuthorizationKafkaListener) have seen on ledger.entry.v1 and
-- card.authorization.v1. Neither is one of the Design detail's four tables
-- (V3__analytics_schema.sql) - they are this service's private staging ground, the raw
-- material tasks 06.2 onward read to populate transaction_category / monthly_spend / budget.
-- Both are pure projections of their topic: drop them, replay from offset zero, and every row
-- comes back identical - no now(), no other side effect, per the phase's central constraint.

-- One row per posted ledger entry (EntryPostedV1). `account_id` is a *ledger* account id -
-- CLAUDE.md rule 7 means this service cannot resolve it to a user by reading another
-- service's database, and neither ledger.entry.v1 nor card.authorization.v1 carries that
-- mapping, so it is left for whichever later task needs it to resolve. Every other ledger
-- event type on this topic (rejections, reservation holds/captures/releases, drift reports) is
-- a state transition rather than a posted fact and is not recorded here - see
-- LedgerEntryKafkaListener's own javadoc.
create table ledger_entry_fact (
    entry_id            uuid primary key,
    account_id          uuid not null,
    direction           text not null,       -- DEBIT | CREDIT
    amount_minor        bigint not null,      -- signed per direction, as EntryPostedV1 carries it
    currency            char(3) not null,
    balance_after_minor bigint not null,
    type                text not null,        -- TRANSFER | FX | CARD_SETTLEMENT | FEE | ADJUSTMENT | REVERSAL
    reference_id        text,
    occurred_at         timestamptz not null   -- event time - the only clock a projection may read
);
create index ledger_entry_fact_account_occurred_idx on ledger_entry_fact (account_id, occurred_at);

-- One row per card authorization, upserted across its lifecycle (approved -> settled /
-- partially settled -> reversed / expired). `AuthorizationDeclinedV1`, `CardIssuedV1`,
-- `AuthorizationRequestedV1`, `SettlementRequestedV1` and `ReversalRequestedV1` are
-- deliberately not projected here: no money ever moved or was held under a decline, and the
-- other four are requests rather than outcomes - see CardAuthorizationKafkaListener's javadoc.
create table card_authorization_fact (
    authorization_id  uuid primary key,
    user_id           uuid not null,
    card_id           uuid not null,
    account_id        uuid not null,
    pan_last4         text not null,
    merchant_name     text,
    merchant_mcc      text,
    merchant_country  text,
    amount_minor      bigint,          -- merchant-currency amount, as authorized
    currency          char(3),         -- merchant currency
    billing_minor     bigint,          -- wallet-currency amount held, as authorized
    billing_currency  char(3),         -- wallet currency
    settled_minor     bigint,          -- wallet-currency amount actually settled, once known
    state             text not null,   -- APPROVED | SETTLED | PARTIALLY_SETTLED | REVERSED | EXPIRED
    authorized_at     timestamptz not null,
    settled_at        timestamptz,
    updated_at        timestamptz not null   -- occurredAt of the last event applied to this row
);
create index card_authorization_fact_user_authorized_idx on card_authorization_fact (user_id, authorized_at);
