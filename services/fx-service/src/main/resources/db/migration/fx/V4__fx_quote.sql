-- Quote lifecycle table (task 04.4 onward). Created here alongside fx_rate because the schema
-- for both tables is decided together in plans/phase-04-fx-multicurrency.md's Design detail;
-- no code in this task writes to it yet.
create table fx_quote (
    id              uuid primary key,
    idempotency_key text not null unique,
    user_id         uuid not null,
    sell_currency   char(3) not null,
    buy_currency    char(3) not null,
    sell_minor      bigint not null,
    buy_minor       bigint not null,
    mid_rate        numeric(20,10) not null,
    client_rate     numeric(20,10) not null,   -- mid +/- spread
    spread_bps      int not null,
    reservation_id  uuid,                      -- hold on the sell account
    state           text not null,             -- ACTIVE | CONSUMED | EXPIRED | CANCELLED
    expires_at      timestamptz not null,
    created_at      timestamptz not null default now()
);
create index on fx_quote (state, expires_at);
