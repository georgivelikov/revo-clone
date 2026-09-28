-- Task 05.6. plans/phase-05-cards.md fixes the settlement posting as
-- "DEBIT user wallet / CREDIT system:card-settlement", which needs an account of that name to
-- credit. V5__system_accounts.sql seeded the four types phase 02's schema listed; CARD_SETTLEMENT
-- was already a LedgerAccountType but had no rows, because until settlement existed nothing could
-- post against it.
--
-- Same id scheme V5 documents and the same four currencies, continued rather than reinvented: the
-- digit before the currency digit is the type (1=FX_POOL, 2=FEE_INCOME, 3=FX_PNL,
-- 4=EXTERNAL_CLEARING, and now 5=CARD_SETTLEMENT), and the last digit is the currency
-- (1=EUR, 2=GBP, 3=USD, 4=JPY). Fixed literals for the same reason V5's are: SystemAccountRefs
-- names these accounts as compile-time constants, so their ids have to be stable in every
-- environment this migration ever runs against.
--
-- This account is where card money leaves the bank for the merchant's acquirer. It goes
-- persistently negative as cards are spent on, which is correct and is what makes it a system
-- account: LedgerAccountType.isSystemAccount() exempts it from the never-negative rule, exactly as
-- it exempts external-clearing on the funding path.

insert into ledger_account (id, owner_ref, type, currency) values
    ('00000000-0000-4000-8000-000000000051', 'system:card-settlement', 'CARD_SETTLEMENT', 'EUR'),
    ('00000000-0000-4000-8000-000000000052', 'system:card-settlement', 'CARD_SETTLEMENT', 'GBP'),
    ('00000000-0000-4000-8000-000000000053', 'system:card-settlement', 'CARD_SETTLEMENT', 'USD'),
    ('00000000-0000-4000-8000-000000000054', 'system:card-settlement', 'CARD_SETTLEMENT', 'JPY');

-- Every ledger_account needs exactly one account_balance row - see V5's note on why.
insert into account_balance (ledger_account_id, available_minor, reserved_minor, currency, version)
select id, 0, 0, currency, 0
from ledger_account
where owner_ref = 'system:card-settlement';
