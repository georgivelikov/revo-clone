-- plans/phase-02-ledger.md, ## Design detail > ### The balancing invariant.
--
-- Enforced twice, deliberately: PostingEngine validates before inserting (task 02.4), and
-- this trigger checks again at commit. Application code eventually gets bypassed - by a
-- migration, a support script, a bug - and this is the check that actually holds regardless.
--
-- DEFERRABLE INITIALLY DEFERRED is essential, not optional: postings for one journal_entry
-- are inserted one row at a time, and the entry is only balanced once all of them are in.
-- An immediate (non-deferred) trigger would reject the first posting of every entry, since a
-- single posting alone is never balanced on its own.

create or replace function assert_entry_balanced() returns trigger as $$
declare
    drift record;
begin
    select currency,
           sum(case when direction = 'CREDIT' then amount_minor else -amount_minor end) as net
    into drift
    from posting
    where entry_id = new.entry_id
    group by currency
    having sum(case when direction = 'CREDIT' then amount_minor else -amount_minor end) <> 0
    limit 1;

    if found then
        raise exception 'journal_entry % is not balanced for currency %: net % minor units',
            new.entry_id, drift.currency, drift.net;
    end if;

    return new;
end;
$$ language plpgsql;

create constraint trigger posting_balanced
after insert on posting
deferrable initially deferred
for each row execute function assert_entry_balanced();
