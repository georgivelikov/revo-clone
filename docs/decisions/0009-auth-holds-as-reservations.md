# 0009. Auth holds modelled as reservations, not postings

Date: 2026-10-03

## Context

A card authorization and an FX quote both need to earmark money before it's actually certain to
move: a card authorization promises a merchant funds will be available at settlement, which may
be hours or days later and may never happen at all (an expired or declined authorization);
an FX quote promises a client rate for 30 seconds. Both need the earmarked amount excluded from
what the user can spend elsewhere, and both need to cleanly undo that earmark if the promise
expires.

## Decision

A `reservation` row, not a journal entry. Placing a hold moves money from `available` to
`reserved` on the `account_balance` cache directly — `available -= amount`, `reserved += amount`
— with no posting and no journal entry; it is a promise, not a transfer. Capturing a reservation
(in full or in part) is what finally posts an entry, with `reservation_id` set on the capturing
posting so its balance effect is `reserved -= amount` only — the money already left `available`
at hold time, so the capture must not decrement it a second time. An expired or released
reservation needs only a state change (`HELD → EXPIRED`/`RELEASED`, `reserved -= amount`,
`available += amount`) with no entry to reverse, because none was ever posted.

## Consequences

**Good.** There is no entry to reverse for the overwhelmingly common case of a hold that expires
or is never captured — a credit-card authorization hold that a merchant never settles is simply a
state transition, not a compensating transaction that has to itself balance against an entry
that, with hindsight, should never have existed. The ledger never depends on the orchestrator
(`fx-service` or `card-service`) coming back to clean up: a scheduled sweep owned by
`ledger-service` itself releases expired holds, so a crashed orchestrator cannot leave money
stuck in limbo indefinitely.

**Bad.** This is a second, parallel set of balance-column semantics layered on top of ordinary
postings, and it is the one place in the ledger's data model where "which column does this touch"
is genuinely easy to get backwards — a capture that decrements `available` a second time still
leaves every posting balanced and the zero-sum invariant still passes, because the invariant
checks postings sum to zero, not that the balance cache is internally consistent. That failure
mode is invisible to the project's own headline safety check and is only caught by comparing
against `available + reserved` together, which is why the balance-column-semantics table is
called out explicitly in `docs/architecture.md` rather than left to be inferred from the schema.
Partial captures also require a second case (release the remainder) that a simpler full-capture-
only design wouldn't need.
