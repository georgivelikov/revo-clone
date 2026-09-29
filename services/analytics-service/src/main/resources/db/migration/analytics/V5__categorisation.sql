-- Task 06.2 (plans/phase-06-vaults-analytics.md): the categorisation resolution mechanism's
-- own tables and their seed data. `transaction_category` itself already exists
-- (V3__analytics_schema.sql, task 06.1's up-front scaffold) - this migration adds the three
-- tables the resolver consults, first match wins:
--
--   1. merchant_category_override  - a user's own correction for one merchant, highest priority
--   2. merchant_rule                - a seeded pattern -> category mapping (`TESCO%` -> Groceries)
--   3. mcc_category                 - the ISO MCC fallback
--
-- All three are natural-keyed (no surrogate id) since none is ever referenced by id from
-- elsewhere - the same reasoning as `budget`'s `unique (user_id, category, month, currency)`
-- rather than a surrogate key plus a unique index.

-- The fallback layer: ISO 18245 merchant category code -> one of the fixed categories. Not
-- exhaustive - a code with no row here falls through to CategorisationResolver's DEFAULT/Other,
-- by design. `Income` and `Transfers` have no natural MCC (a card authorization is always an
-- outgoing spend, never a salary credit or an internal transfer) and so are deliberately absent
-- from this table; those two categories are reachable only via a merchant rule or a user
-- override for now.
create table mcc_category (
    mcc      text primary key,
    category text not null
);

insert into mcc_category (mcc, category) values
    ('5411', 'Groceries'),      -- Grocery stores, supermarkets
    ('5422', 'Groceries'),      -- Freezer / meat provisioners
    ('5812', 'Restaurants'),    -- Eating places, restaurants
    ('5814', 'Restaurants'),    -- Fast food restaurants
    ('4121', 'Transport'),      -- Taxicabs / limousines
    ('4111', 'Transport'),      -- Local / suburban commuter transport
    ('5541', 'Transport'),      -- Service stations (fuel)
    ('5311', 'Shopping'),       -- Department stores
    ('5651', 'Shopping'),       -- Family clothing stores
    ('5999', 'Shopping'),       -- Miscellaneous retail
    ('7832', 'Entertainment'),  -- Motion picture theatres
    ('7922', 'Entertainment'),  -- Theatrical producers, ticketing
    ('5815', 'Entertainment'),  -- Digital goods: streaming media
    ('4900', 'Bills'),          -- Utilities
    ('4814', 'Bills'),          -- Telecommunication services
    ('8011', 'Health'),         -- Doctors, physicians
    ('5912', 'Health'),         -- Drug stores, pharmacies
    ('8062', 'Health'),         -- Hospitals
    ('4511', 'Travel'),         -- Airlines
    ('7011', 'Travel'),         -- Hotels, motels
    ('6011', 'Cash');           -- ATM cash disbursements

-- The middle layer: a name pattern (SQL LIKE syntax - `%` any run of characters, `_` any single
-- character - matched case-insensitively by CategorisationResolver) beats the MCC fallback but
-- loses to a user override. `priority` breaks ties when more than one pattern matches the same
-- merchant name; lower runs first. None of the seeded patterns currently overlap, so every row
-- shares the same default.
create table merchant_rule (
    pattern  text primary key,
    category text not null,
    priority int not null default 100
);

insert into merchant_rule (pattern, category) values
    ('TESCO%', 'Groceries'),
    ('SAINSBURY%', 'Groceries'),
    ('ALDI%', 'Groceries'),
    ('STARBUCKS%', 'Restaurants'),
    ('MCDONALD%', 'Restaurants'),
    ('NETFLIX%', 'Entertainment'),
    ('SPOTIFY%', 'Entertainment'),
    ('SHELL%', 'Transport'),
    ('AMAZON%', 'Shopping');

-- The highest-priority layer: a user's own correction, remembered for that merchant so future
-- transactions from it categorise the way the user wants without needing a rule seeded here.
-- Composite natural key: one override per (user, merchant name). Setting an override again for
-- the same pair replaces the previous one (upsert), it does not create a second row.
create table merchant_category_override (
    user_id       uuid not null,
    merchant_name text not null,
    category      text not null,
    updated_at    timestamptz not null,
    primary key (user_id, merchant_name)
);
