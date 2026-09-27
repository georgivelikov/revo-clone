-- History of sampled mid rates, per pair - not yet written to by this task (04.2 only builds the
-- in-memory cache, see FxRateCache, which is what quoting will always read from). Created now
-- because the schema for fx_rate and fx_quote is decided together in
-- plans/phase-04-fx-multicurrency.md's Design detail. Only canonical pairs (e.g. "EUR/GBP",
-- never "GBP/EUR") are ever meant to be written here.
create table fx_rate (
    pair        text not null,
    mid         numeric(20,10) not null,
    as_of       timestamptz not null,
    source      text not null,
    primary key (pair, as_of)
);
