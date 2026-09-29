-- plans/phase-06-vaults-analytics.md, ## Design detail. Verbatim - do not modify.
--
-- Starts at V3, not V1: Flyway resolves all `flyway.locations` into one flat version
-- sequence, so V1 and V2 are reserved project-wide for common-messaging's shared
-- db/migration/common/V1__outbox_event.sql and V2__processed_event.sql. See
-- docs/spring-boot-4-notes.md and ledger-service's V3__ledger.sql, the precedent this
-- migration follows.
--
-- This is task 06.1's scaffold migration: it lays down the whole Design-detail schema up
-- front, even though the Java code that populates most of these tables lands in later tasks
-- (06.2 categorisation, 06.3 monthly_spend, 06.5 budgets, 06.6 vaults). Task 06.1 itself
-- writes to none of these four tables - see V4__event_read_models.sql for the tables its own
-- consumers populate.

-- A vault is a VAULT-type ledger account (task 06.6). `account_id` is account-service's
-- account id, not ledger-service's - the money itself lives in ledger-service, the account
-- lives in account-service, and this row is analytics-service's own record of which vault
-- belongs to which user. See the phase's "Vaults" section for why the split is deliberate.
create table vault (
    id                uuid primary key,
    user_id           uuid not null,
    account_id        uuid not null,       -- the VAULT account
    name              text not null,
    currency          char(3) not null,
    goal_minor        bigint,
    roundup_enabled   boolean not null default false,
    roundup_source_card_id uuid,
    created_at        timestamptz not null default now()
);

-- Three-layer categorisation (task 06.2): user override beats merchant rule beats MCC
-- mapping. One row per card authorization, keyed on the authorization id so a user override
-- (task 06.4's retro-apply) can find and correct exactly the transactions it changes.
create table transaction_category (
    authorization_id  uuid primary key,
    user_id           uuid not null,
    category          text not null,
    source            text not null,       -- USER | MERCHANT_RULE | MCC | DEFAULT
    assigned_at       timestamptz not null
);

-- Incremental per-user, per-month, per-category, per-currency spend (task 06.3). Updated by
-- an upsert (`total_minor = total_minor + excluded.total_minor`) as events arrive, never by
-- re-scanning - and never summed across currencies, per the phase's own multi-currency
-- warning.
create table monthly_spend (
    user_id     uuid not null,
    month       date not null,            -- first of month
    category    text not null,
    currency    char(3) not null,
    total_minor bigint not null,
    txn_count   int not null,
    primary key (user_id, month, category, currency)
);

-- Budget threshold alerting (task 06.5). `alerted_at_80`/`alerted_at_100` are the idempotency
-- guard that stops a full topic replay from re-notifying a user for six months of history -
-- see the phase's "This is the trap in replayable projections" note.
create table budget (
    id            uuid primary key,
    user_id       uuid not null,
    category      text not null,
    month         date not null,
    limit_minor   bigint not null,
    currency      char(3) not null,
    alerted_at_80 timestamptz,
    alerted_at_100 timestamptz,
    unique (user_id, category, month, currency)
);
