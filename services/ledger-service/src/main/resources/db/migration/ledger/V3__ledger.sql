-- plans/phase-02-ledger.md, ## Design detail > ### Schema. Verbatim - do not modify.
--
-- Starts at V3, not V1: Flyway resolves all `flyway.locations` into one flat version
-- sequence, so V1 and V2 are reserved project-wide for common-messaging's shared
-- db/migration/common/V1__outbox_event.sql and V2__processed_event.sql. See
-- docs/spring-boot-4-notes.md.
--
-- Table order is deliberate: `reservation` is declared before `posting` because
-- `posting.reservation_id` references it - reversing the order makes the forward
-- reference fail to apply. See the phase spec's own note on this.

create table ledger_account (
    id          uuid primary key,
    owner_ref   text not null,            -- 'user:<uuid>' | 'system:fx-pool' | ...
    type        text not null,            -- USER_WALLET | VAULT | FX_POOL
                                          -- CARD_SETTLEMENT | FEE_INCOME | FX_PNL
                                          -- EXTERNAL_CLEARING
    currency    char(3) not null,
    created_at  timestamptz not null default now(),
    unique (owner_ref, type, currency)
);

create table journal_entry (
    id              uuid primary key,
    idempotency_key text not null unique,
    type            text not null,        -- TRANSFER | FX | CARD_SETTLEMENT | FEE | ADJUSTMENT | REVERSAL
    reference_id    uuid,                 -- the payment/authorization that caused it
    reverses_entry_id uuid references journal_entry(id),
    metadata        jsonb not null default '{}',
    created_at      timestamptz not null default now()
);

create table reservation (
    id                uuid primary key,
    ledger_account_id uuid not null references ledger_account(id),
    amount_minor      bigint not null check (amount_minor > 0),
    currency          char(3) not null,
    state             text not null,      -- HELD | CAPTURED | RELEASED | EXPIRED
    reference_id      uuid not null,
    idempotency_key   text not null unique,
    expires_at        timestamptz not null,
    created_at        timestamptz not null default now(),
    resolved_at       timestamptz
);
create index on reservation (state, expires_at);

create table posting (
    id                uuid primary key,
    entry_id          uuid not null references journal_entry(id),
    ledger_account_id uuid not null references ledger_account(id),
    direction         text not null check (direction in ('DEBIT','CREDIT')),
    amount_minor      bigint not null check (amount_minor > 0),
    currency          char(3) not null,
    reservation_id    uuid references reservation(id),   -- set when this posting captures a hold
    seq               int not null,
    unique (entry_id, seq)
);
create index on posting (ledger_account_id, id);

create table account_balance (
    ledger_account_id uuid primary key references ledger_account(id),
    available_minor   bigint not null default 0,
    reserved_minor    bigint not null default 0 check (reserved_minor >= 0),
    currency          char(3) not null,
    version           bigint not null default 0        -- @Version, optimistic lock
);
