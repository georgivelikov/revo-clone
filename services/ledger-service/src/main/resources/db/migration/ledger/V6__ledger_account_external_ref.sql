-- Task 03.4: ledger-service consumes PaymentInitiatedV1, whose senderAccountId/receiverAccountId
-- are *account-service* account ids - the only account identity payment-service has. Posting the
-- transfer needs the ledger's own ledger_account ids, and until now nothing in this schema
-- recorded which ledger account belongs to which account-service account: ledger_account.owner_ref
-- is 'user:<userId>', which does not identify one of a user's several wallets.
--
-- This column is that link, and it is not new information - it is exactly the pair ledger-service
-- already publishes in LedgerAccountCreatedV1(accountId, ledgerAccountId) after consuming
-- AccountOpenedV1 (task 02.8). It was simply never stored on this side. Storing it keeps the
-- resolution local, which is what lets the ledger post a transfer with account-service down, and
-- avoids the alternative of teaching account-service to echo ledgerAccountId back onto
-- account.account.v1 so payment-service could forward it - three services changed to carry a fact
-- this one already knows.
--
-- Nullable, because system accounts (FX_POOL, FEE_INCOME, EXTERNAL_CLEARING, ... - seeded by
-- V5__system_accounts.sql) have no account-service counterpart and never will. Unique, because two
-- ledger accounts claiming the same account-service account would make the mapping ambiguous and
-- silently post one user's transfer against another's wallet; Postgres treats NULLs as distinct in
-- a unique index, so the system accounts do not collide with each other.
alter table ledger_account
    add column external_account_id uuid;

create unique index ledger_account_external_account_id_key
    on ledger_account (external_account_id);
