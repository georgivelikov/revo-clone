-- plans/phase-02-ledger.md, ### Schema: `ledger_account.type` lists FX_POOL, FEE_INCOME,
-- FX_PNL and EXTERNAL_CLEARING as system account types, one per supported currency. The
-- currency set (EUR, GBP, USD, JPY) is the union of phase 01's three starter wallet
-- currencies (identity/account-service open EUR/GBP/USD on registration) and phase 04's
-- eventual JPY addition (its own task list: "extend the seed script with GBP/USD/JPY
-- balances") - seeding all four now means phase 04 needs no new system-account migration.
--
-- Ids are fixed, deterministic literals, not gen_random_uuid(): these sixteen accounts are
-- referenced by later code as well-known constants (see SystemAccountRefs), not discovered by
-- query at startup, so their ids must be stable across every environment this migration ever
-- runs against. The scheme is transparent by construction, not obscured: the last two hex
-- digits before the final segment are 1=FX_POOL/2=FEE_INCOME/3=FX_PNL/4=EXTERNAL_CLEARING,
-- then 1=EUR/2=GBP/3=USD/4=JPY, e.g. ...044 is EXTERNAL_CLEARING/JPY. Both digits are valid
-- hex (1-4), so this reads as a normal-shaped v4 UUID rather than a special case Flyway or
-- Postgres would need to treat differently.
--
-- Every ledger_account needs exactly one account_balance row, per every other account-creation
-- path in this service (AccountBalance's own constructor always zero-initialises both
-- columns) - a ledger_account with no matching account_balance would 500 the first time
-- anything tried to read or post against it.

insert into ledger_account (id, owner_ref, type, currency) values
    ('00000000-0000-4000-8000-000000000011', 'system:fx-pool',           'FX_POOL',           'EUR'),
    ('00000000-0000-4000-8000-000000000012', 'system:fx-pool',           'FX_POOL',           'GBP'),
    ('00000000-0000-4000-8000-000000000013', 'system:fx-pool',           'FX_POOL',           'USD'),
    ('00000000-0000-4000-8000-000000000014', 'system:fx-pool',           'FX_POOL',           'JPY'),
    ('00000000-0000-4000-8000-000000000021', 'system:fee-income',        'FEE_INCOME',        'EUR'),
    ('00000000-0000-4000-8000-000000000022', 'system:fee-income',        'FEE_INCOME',        'GBP'),
    ('00000000-0000-4000-8000-000000000023', 'system:fee-income',        'FEE_INCOME',        'USD'),
    ('00000000-0000-4000-8000-000000000024', 'system:fee-income',        'FEE_INCOME',        'JPY'),
    ('00000000-0000-4000-8000-000000000031', 'system:fx-pnl',            'FX_PNL',            'EUR'),
    ('00000000-0000-4000-8000-000000000032', 'system:fx-pnl',            'FX_PNL',            'GBP'),
    ('00000000-0000-4000-8000-000000000033', 'system:fx-pnl',            'FX_PNL',            'USD'),
    ('00000000-0000-4000-8000-000000000034', 'system:fx-pnl',            'FX_PNL',            'JPY'),
    ('00000000-0000-4000-8000-000000000041', 'system:external-clearing', 'EXTERNAL_CLEARING', 'EUR'),
    ('00000000-0000-4000-8000-000000000042', 'system:external-clearing', 'EXTERNAL_CLEARING', 'GBP'),
    ('00000000-0000-4000-8000-000000000043', 'system:external-clearing', 'EXTERNAL_CLEARING', 'USD'),
    ('00000000-0000-4000-8000-000000000044', 'system:external-clearing', 'EXTERNAL_CLEARING', 'JPY');

insert into account_balance (ledger_account_id, available_minor, reserved_minor, currency, version)
select id, 0, 0, currency, 0
from ledger_account
where owner_ref in ('system:fx-pool', 'system:fee-income', 'system:fx-pnl', 'system:external-clearing');
