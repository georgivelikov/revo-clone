-- plans/phase-07-resilience-and-scale.md's ### Replay, flavour 2: "replay ledger.entry.v1 up
-- to a timestamp into a throwaway schema to answer 'what was every balance last Tuesday at
-- 14:00?'". CLAUDE.md's Flyway-owns-all-schema rule means that throwaway table still has to be
-- a real migration rather than ad-hoc DDL issued at request time - so "throwaway" here means
-- its *contents*: PointInTimeService truncates and repopulates every row on every query, never
-- accumulating rows across runs, and this table carries no authoritative balance - only
-- account_balance (V3) does.

create table point_in_time_snapshot (
    ledger_account_id   uuid primary key,
    currency            char(3) not null,
    balance_after_minor bigint not null,
    entry_id            uuid not null,
    occurred_at         timestamptz not null
);
