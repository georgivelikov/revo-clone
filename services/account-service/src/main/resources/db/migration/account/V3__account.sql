-- plans/phase-01-identity-account-gateway.md, ### account-service.
--
-- Starts at V3, not V1: Flyway resolves all `flyway.locations` into one flat version
-- sequence, so V1 and V2 are reserved project-wide for common-messaging's shared
-- db/migration/common/V1__outbox_event.sql and V2__processed_event.sql. See
-- docs/spring-boot-4-notes.md.
--
-- `name` is `not null default ''`, deliberately: Postgres treats NULLs as distinct in a
-- unique constraint, so a nullable name would let this constraint permit unlimited duplicate
-- wallets per user. Wallets always use ''; vaults (Phase 06) will have real names.
create table account (
    id                uuid primary key,
    user_id           uuid not null,
    currency          char(3) not null,
    type              text not null,
    name              text not null default '',
    state             text not null,
    ledger_account_id uuid,
    created_at        timestamptz not null default now(),
    unique (user_id, currency, type, name)
);
create index on account (user_id);
