-- plans/phase-04-fx-multicurrency.md task 8: a cross-currency P2P transfer is the same payment
-- row and the same PENDING -> COMPLETED|FAILED|CANCELLED machine, with three extra facts
-- recorded only when the transfer actually crosses currencies. `currency`/`amount_minor` keep
-- meaning what they already mean - the SOURCE side, what the payer's account moves - so an
-- existing same-currency transfer needs no reinterpretation.
--
-- All three are nullable together: a same-currency transfer sets none of them, and every column
-- comes from the one fx-service quote this payment used, so there is nothing to default
-- independently.
alter table payment
    add column target_currency      char(3),
    add column target_amount_minor  bigint check (target_amount_minor is null or target_amount_minor > 0),
    add column quote_id             uuid;

-- A quote is single-use and belongs to at most one payment - the same fact fx_quote's own
-- idempotency_key uniqueness rests on, seen from this side of the topic. Partial: most rows are
-- same-currency transfers with no quote at all.
create unique index on payment (quote_id) where quote_id is not null;
