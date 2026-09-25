-- plans/phase-03-payments-and-ui.md, ## Design detail > ### `payment-service`. Verbatim -
-- do not modify.
--
-- Starts at V3, not V1: Flyway resolves all `flyway.locations` into one flat version
-- sequence, so V1 and V2 are reserved project-wide for common-messaging's shared
-- db/migration/common/V1__outbox_event.sql and V2__processed_event.sql. See
-- docs/spring-boot-4-notes.md.
--
-- `status` is text, not an enum type: the state machine is enforced in Java
-- (PaymentStatus/Payment.transitionTo), where an illegal transition can throw with a
-- message naming both states, rather than in the database, where it would surface as an
-- opaque constraint violation. Same reason journal_entry.type is text in phase 02.

create table payment (
    id                uuid primary key,
    idempotency_key   text not null,
    initiator_user_id uuid not null,
    source_account_id uuid not null,
    target_account_id uuid not null,
    amount_minor      bigint not null check (amount_minor > 0),
    currency          char(3) not null,
    status            text not null,     -- PENDING | COMPLETED | FAILED | CANCELLED
    failure_reason    text,
    reference         text,              -- user's note
    ledger_entry_id   uuid,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null,
    unique (initiator_user_id, idempotency_key)
);
create index on payment (source_account_id, created_at desc);
