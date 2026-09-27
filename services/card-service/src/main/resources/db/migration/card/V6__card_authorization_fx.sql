-- Task 05.5: the FX path for foreign-currency merchants. plans/phase-05-cards.md's
-- card_authorization already carries billing_minor/billing_currency, which is what the UI needs for
-- "you spent $50, we charged you EUR 40.60". These five columns are what makes that number
-- explicable rather than merely correct, and they are the phase's task-5 requirement made physical:
-- the exchange spread and the card mark-up are two columns, from two services, never one.
--
--   fx_spread_bps   fx-service's exchange spread, its revenue, its configuration
--   fx_markup_bps   card-service's mark-up, its revenue, its configuration
--   fx_markup_minor that mark-up in whole minor units of the wallet's currency - the slice of
--                   billing_minor bound for FEE_INCOME when this authorization settles (task 05.6).
--                   billing_minor - fx_markup_minor is the slice bound for card-settlement; stored
--                   as one number and not two so the two can never disagree with billing_minor.
--
-- Every column is nullable because a domestic authorization has no FX at all, and so does a decline
-- reached before the amount was converted (a frozen or expired card). Null here means "no conversion
-- happened", which is a different fact from zero.
--
-- Rates are text, not numeric(20,10) as fx_rate and fx_quote use. Those tables are read through
-- JdbcClient, which casts to and from text at the query (`mid_rate::text`); card_authorization is a
-- JPA entity, and a numeric column would force a BigDecimal field onto it - which CLAUDE.md hard
-- rule 1 confines to FxMath. A rate is exact decimal text everywhere else in this codebase for the
-- same reason, so text is the representation that needs no conversion rather than a compromise.
alter table card_authorization
    add column fx_mid_rate      text,
    add column fx_exchange_rate text,
    add column fx_spread_bps    int,
    add column fx_markup_bps    int,
    add column fx_markup_minor  bigint;

-- The mark-up is part of what was charged, so it can never exceed it, and it is only ever present
-- together with the rate that produced it. Cheap to state and it catches the one bug that would be
-- invisible until a settlement entry failed to balance days later.
alter table card_authorization
    add constraint card_authorization_fx_markup_within_billing
        check (fx_markup_minor is null
               or (billing_minor is not null and fx_markup_minor between 0 and billing_minor)),
    add constraint card_authorization_fx_rate_complete
        check ((fx_mid_rate is null) = (fx_exchange_rate is null)
               and (fx_mid_rate is null) = (fx_markup_minor is null));
