-- Task 05.3. The authorization chain's step 6 holds funds on the card's funding wallet, and the
-- ledger's reservation API is addressed by *ledger* account id in the wallet's own currency. A
-- `card` row carries only `account_id` (an account-service account), so card-service has to answer
-- two questions locally: what currency does that wallet hold, and which ledger account is behind
-- it.
--
-- Locally, because it cannot be otherwise. CLAUDE.md rule 7 forbids reading another service's
-- database, and plans/phase-05-cards.md puts a hard bound on the alternative: "steps 1-6 must
-- complete in well under a second - a real network times out around 2-5 s [...] this is why the
-- decision cannot wait on a downstream service that might be slow." So the two facts are projected
-- from the events that already carry them, exactly as fx-service (V5__fx_account_read_model.sql),
-- payment-service (V4__account_view.sql) and notification-service do for the same question.
--
-- Neither table is a source of truth: both are rebuilt from their topics on every partition
-- assignment and could be dropped at any time.

-- Projection of AccountOpenedV1 on the compacted account.account.v1. Keyed by account_id because
-- that is what a card points at - unlike fx-service, which asks "the caller's wallet in this
-- currency" and therefore indexes (user_id, currency).
create table funding_account (
    account_id  uuid        primary key,
    user_id     uuid        not null,
    currency    char(3)     not null,
    type        text        not null,     -- WALLET | VAULT, as account-service spells it
    updated_at  timestamptz not null      -- occurredAt of the last event applied to this row
);

-- Projection of LedgerAccountCreatedV1 on ledger.entry.v1: which account-service account a ledger
-- account belongs to. Its own table rather than a nullable column on funding_account, following
-- fx-service's precedent for the identical pair of facts: they arrive on two different topics in no
-- guaranteed order, and a projection where neither row depends on the other existing yet needs no
-- ordering logic at all.
create table ledger_account_ref (
    ledger_account_id uuid        primary key,
    account_id        uuid        not null unique,
    updated_at        timestamptz not null    -- occurredAt of the last event applied to this row
);
