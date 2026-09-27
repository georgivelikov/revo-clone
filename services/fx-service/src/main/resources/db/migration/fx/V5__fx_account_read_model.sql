-- Task 04.4 needs to answer one question before it can reserve anything: "which ledger account
-- holds this user's EUR?" plans/phase-04-fx-multicurrency.md's quoting steps say "Reserve the sell
-- amount on the user's wallet via the ledger", while the request body carries only currencies and
-- an amount - the wallet is implied by the caller's JWT subject.
--
-- CLAUDE.md rule 7 means fx-service cannot read account-service's or ledger-service's database to
-- find out, and the phase's own design keeps sideways HTTP down to the single documented
-- payment -> fx quote call. So the answer is projected from the two events that already carry it,
-- exactly as payment-service (V4__account_view.sql) and notification-service
-- (V3__notification.sql) do for the same question. Neither table is a source of truth: both are
-- rebuilt from their topics on every partition assignment and could be dropped at any time.

-- Projection of AccountOpenedV1 on the compacted account.account.v1: who owns an account-service
-- account and what currency it holds.
create table account_owner (
    account_id  uuid        primary key,
    user_id     uuid        not null,
    currency    char(3)     not null,
    type        text        not null,     -- WALLET | VAULT, as account-service spells it
    updated_at  timestamptz not null      -- occurredAt of the last event applied to this row
);

-- The quoting path's only read: "the caller's WALLET in this currency". Deliberately not unique:
-- account-service's own constraint is unique(user_id, currency, type, *name*), so a user may hold
-- several VAULTs in one currency, and a projection that could raise a constraint violation is a
-- projection that can stall its consumer over a row nothing here even reads.
create index account_owner_user_currency_idx
    on account_owner (user_id, currency);

-- Projection of LedgerAccountCreatedV1 on ledger.entry.v1: which account-service account a ledger
-- account belongs to. The reservation API is addressed by *ledger* account id, and nothing on
-- account.account.v1 carries that - ledger-service publishes this event precisely so the mapping
-- is available to whoever needs it (account-service uses it to fill its own ledger_account_id).
--
-- Its own table rather than a nullable column on account_owner, following notification-service's
-- precedent for the identical pair of facts: they arrive on two different topics in no guaranteed
-- order, and a projection where neither row depends on the other existing yet needs no ordering
-- logic at all.
create table ledger_account_ref (
    ledger_account_id uuid        primary key,
    account_id        uuid        not null unique,
    updated_at        timestamptz not null    -- occurredAt of the last event applied to this row
);
