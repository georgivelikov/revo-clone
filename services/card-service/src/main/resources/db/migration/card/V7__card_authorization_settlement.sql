-- Task 05.6: the settlement lifecycle. plans/phase-05-cards.md's card_authorization already carries
-- state, ledger_entry_id and settled_at, which cover the exact case entirely: state becomes SETTLED,
-- ledger_entry_id names the posting that had not existed until then, settled_at says when. These
-- three columns are what the other two cases need on top of that.
--
--   settled_minor            what the merchant actually took, in the billing currency. Equal to
--                            billing_minor on an exact settlement, less on a partial, more on an
--                            over-settlement - which is exactly why it cannot be inferred from
--                            billing_minor and has to be its own column. It is also the figure the
--                            feed shows once a row stops being pending.
--   excess_minor             the part of settled_minor that went beyond the hold. This is the "flag"
--                            in the phase's "may overdraw; allow it and flag": an over-settlement is
--                            the one path that can legitimately push a wallet negative, and a
--                            queryable column is what stops that being something only a log line
--                            remembers. Null (not zero) when no settlement has happened yet.
--   excess_ledger_entry_id   the second journal entry, which posted the excess. Two entries because
--                            the excess is not a capture of anything - no hold covers it, so it is an
--                            ordinary debit and is accounted for as one.
--
-- Every column is nullable: an APPROVED authorization has not settled, and a DECLINED one never will.
alter table card_authorization
    add column settled_minor          bigint,
    add column excess_minor           bigint,
    add column excess_ledger_entry_id uuid;

-- A settled row has a settled amount, a posting and a timestamp, or it is not a settled row. Stated
-- as a constraint rather than trusted to the service because the alternative - a SETTLED row with no
-- ledger_entry_id - is precisely a transaction the holder can see and the ledger has never heard of.
alter table card_authorization
    add constraint card_authorization_settled_is_posted
        check (state not in ('SETTLED', 'PARTIALLY_SETTLED')
               or (settled_minor is not null and settled_minor > 0
                   and ledger_entry_id is not null and settled_at is not null)),
    -- And the reverse: nothing that has not settled may carry settlement evidence. This is what
    -- catches a reversal or an expiry that wrote a posting id it had no business having, since
    -- neither of those ever creates one.
    add constraint card_authorization_unsettled_has_no_posting
        check (state in ('SETTLED', 'PARTIALLY_SETTLED')
               or (settled_minor is null and ledger_entry_id is null
                   and excess_minor is null and excess_ledger_entry_id is null)),
    -- An excess is never negative, and a row that names the excess entry must have an excess to
    -- explain it. The excess itself may be zero, which is the ordinary case and a different fact
    -- from null: zero means "this settled and took nothing extra", null means "this has not settled".
    add constraint card_authorization_excess_consistent
        check ((excess_minor is null or excess_minor >= 0)
               and (excess_ledger_entry_id is null or coalesce(excess_minor, 0) > 0));
