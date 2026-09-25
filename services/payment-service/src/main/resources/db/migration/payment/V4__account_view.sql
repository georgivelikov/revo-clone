-- plans/phase-03-payments-and-ui.md, ## Design detail > ### `payment-service`: "Account state
-- comes from a local read model in payment-service, built by consuming account.account.v1 -
-- not a synchronous call to account-service."
--
-- This table is that read model: payment-service's own copy of the handful of account facts
-- POST /api/payments has to check before accepting a transfer (who owns the account, what
-- currency it holds, whether it is ACTIVE). It lives in payment-service's own database, so the
-- "every service owns its database" and "no sideways HTTP" rules both hold - a transfer can be
-- validated with account-service down.
--
-- It is a projection, never a source of truth. Every row is derived from an event on the
-- compacted account.account.v1 topic and the whole table can be dropped and rebuilt from that
-- topic - AccountOpenedKafkaListener seeks its partitions to the beginning on every assignment
-- precisely so a fresh instance rebuilds its view on startup. Nothing but the consumer ever
-- writes here, which is why there is no created_at/version/audit column: the log is the audit
-- trail.
--
-- V4, not V1: Flyway flattens every `flyway.locations` entry into one version sequence, so V1
-- and V2 are reserved project-wide for common-messaging's shared migrations and V3 is this
-- service's own payment table. See docs/spring-boot-4-notes.md.

create table account_view (
    account_id  uuid        primary key,
    user_id     uuid        not null,
    currency    char(3)     not null,
    type        text        not null,     -- WALLET | VAULT, as account-service spells it
    state       text        not null,     -- ACTIVE | FROZEN | CLOSED
    updated_at  timestamptz not null      -- occurredAt of the last event applied to this row
);

-- Every validation path starts from "which accounts does this caller own", and so does the
-- recipients feed.
create index on account_view (user_id);
