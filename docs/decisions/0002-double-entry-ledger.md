# 0002. Double-entry ledger over mutable balance columns

Date: 2026-10-03

## Context

Every payment, exchange, and card settlement in the system needs to move money between two
accounts in a way that can be audited after the fact and never silently drifts. A `balance`
column that an `UPDATE ... SET balance = balance + ?` statement mutates in place is the obvious
first design — and the one every naive implementation reaches for — but it has no history: once
the next transaction overwrites it, there is no way to prove the column is correct, only to
trust that every statement that ever touched it was right.

## Decision

`ledger-service` is append-only. Money never moves by updating a number; it moves by inserting
a `journal_entry` with two or more immutable `posting` rows that must sum to zero per currency.
A `account_balance` cache is maintained in the same transaction as the postings that affect it,
purely as a read-optimization — the postings are the source of truth, the cache is derived. The
invariant is enforced twice: once in `PostingEngine` before insert, and once more by a
`DEFERRABLE INITIALLY DEFERRED` database trigger checked at commit, because application code
eventually gets bypassed by a migration, a support script, or a bug, and the trigger is the
thing that actually holds when it does.

## Consequences

**Good.** The zero-sum invariant (`SUM(amount) == 0` per `entry_id`, across every posting ever
made) is checkable at any time against the full history, not just at write time — it's a single
SQL query, run continuously in this project's own CI gate. A disputed transaction can be traced
to the exact postings that produced a balance, which a mutated column can never offer. The
balance cache can be dropped and rebuilt from the postings alone if it's ever suspected of
drifting — it is not itself authoritative.

**Bad.** Every money movement is now at least two tables and two writes instead of one `UPDATE`,
and the balance-cache update has to happen in the same transaction as the postings or the two
diverge. The sign convention (a wallet is a liability, so a CREDIT *increases* the customer's
money) is a case of "this is backwards from how a naive implementer's intuition runs" and is the
single most common bug class the design invites — getting it wrong is invisible until someone
reads a statement, which is exactly why it's documented as a comment at the top of
`PostingEngine` itself, not just in this ADR. The reservation mechanism (holds that move money
between `available` and `reserved` without posting an entry — see
[ADR 0009](0009-auth-holds-as-reservations.md)) adds a second set of column semantics on top of
postings, which is one more thing a reader has to hold in their head before the ledger's
behavior is fully predictable.
