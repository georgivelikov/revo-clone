-- plans/phase-03-payments-and-ui.md, ## Design detail > ### `notification-service`:
-- "Persists notifications so the UI can show a history and an unread badge."
--
-- V3, not V1: Flyway flattens every `flyway.locations` entry into one version sequence, so V1
-- and V2 are reserved project-wide for common-messaging's shared outbox/processed_event
-- migrations. See docs/spring-boot-4-notes.md.
--
-- Three of the four tables here are projections, not sources of truth. This service owns no
-- money and no user record; it owns "what has this user been told". Everything else it needs -
-- who owns an account, which ledger account belongs to which wallet, who the two parties to a
-- payment are - is a local copy of facts another service published, because CLAUDE.md rule 7
-- means it cannot read account-service's or payment-service's database and the phase's design
-- forbids a synchronous call in their place.

-- The feed and the unread badge. One row per thing a user would still want to see tomorrow.
create table notification (
    id              uuid        primary key,
    user_id         uuid        not null,
    type            text        not null,     -- MONEY_IN | MONEY_OUT | TRANSFER_FAILED | PUSH
    title           text        not null,
    body            text,
    payment_id      uuid,                     -- set when this notification is about a transfer
    amount_minor    bigint,                   -- signed, minor units; never a fractional type
    currency        char(3),
    correlation_id  text,                     -- the causing event's, so a push is traceable end to end
    read_at         timestamptz,              -- null = unread, which is the whole badge query
    -- occurredAt of the event that caused this, never now(): the feed is ordered by when the
    -- money moved, not by when this consumer happened to catch up.
    created_at      timestamptz not null
);

-- The feed query and the unread count are the only two reads this table has.
create index on notification (user_id, created_at desc);
create index on notification (user_id) where read_at is null;

-- Projection of account.account.v1 (compacted): which user owns an account-service account.
-- Rebuilt from offset 0 on every partition assignment, so it is disposable by construction.
create table account_owner (
    account_id  uuid        primary key,
    user_id     uuid        not null,
    currency    char(3)     not null,
    updated_at  timestamptz not null      -- occurredAt of the last event applied to this row
);
create index on account_owner (user_id);

-- Projection of LedgerAccountCreatedV1 on ledger.entry.v1: which account-service account a
-- ledger account belongs to. EntryPostedV1 names its account by *ledger* account id (it is keyed
-- by it), and nothing else on that event identifies a human - so without this hop there is no way
-- to turn "money moved on ledger account X" into "tell this user". Deliberately its own table
-- rather than a nullable column on account_owner: the two facts arrive on two different topics in
-- no guaranteed order, and a projection that has to cope with either arriving first is simpler
-- when neither row depends on the other existing yet.
create table ledger_account_ref (
    ledger_account_id uuid primary key,
    account_id        uuid not null,
    updated_at        timestamptz not null    -- occurredAt of the last event applied to this row
);
create index on ledger_account_ref (account_id);

-- Projection of PaymentInitiatedV1 on payment.event.v1: who the two parties to a payment are.
-- PaymentCompletedV1 and PaymentFailedV1 carry only a paymentId, so without this there is no way
-- to answer "whose screens does this belong on" - and "both parties" is exactly what the phase's
-- saga step 4 asks the fan-out to reach.
create table payment_parties (
    payment_id          uuid        primary key,
    sender_account_id   uuid        not null,
    receiver_account_id uuid        not null,
    amount_minor        bigint      not null check (amount_minor > 0),
    currency            char(3)     not null,
    reference           text,                    -- the sender's note, shown in the notification
    created_at          timestamptz not null     -- occurredAt of PaymentInitiatedV1
);
