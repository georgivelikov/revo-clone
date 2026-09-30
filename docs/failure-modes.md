# Failure modes

Real observations from running the eleven experiments in [`plans/phase-07-resilience-and-scale.md`](../plans/phase-07-resilience-and-scale.md)
against the full stack (`docker compose -f docker-compose.infra.yml -f docker-compose.yml -f
docker-compose.chaos.yml --profile services up -d --build`, i.e. every service rebuilt from the
code that landed in tasks 07.1–07.9, with the `chaos` profile active on `simulator-service`,
`ledger-service`, `fx-service` and `payment-service` — see `docker-compose.chaos.yml`). Two test
users (`alice.seed@revolutclone.local`, `bob.seed@revolutclone.local`, both funded in EUR/GBP/USD/JPY
via `tasks.ps1 seed`) generated the load. Every experiment's before/after state was checked against
the ledger's zero-sum invariant:

```sql
SELECT entry_id FROM posting
GROUP BY entry_id
HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0;
```

**This is not the query CLAUDE.md and `agent/gate.ps1` state.** See [§0](#0-a-gate-that-has-never-actually-run)
below — it's the first and most important finding, because it means every prior phase's gate run
believed it was checking this invariant continuously and never actually was.

Three genuine bugs turned up along the way, independent of any chaos lever — they are documented
where they were found (§2, §7) with the fix applied, since two of the eleven experiments could not
otherwise be observed as designed. One of those (§1) is the headline finding of this phase: it
explains why three of the eleven experiments didn't resolve the way the phase predicted.

## 0. A gate that has never actually run

`CLAUDE.md`'s Ledger invariant section and `agent/gate.ps1`'s check 6 both query:

```sql
SELECT transaction_id FROM ledger_entry GROUP BY transaction_id HAVING SUM(amount_minor) <> 0;
```

Neither `ledger_entry` nor `transaction_id` exists. The real schema (`V1__ledger.sql` onward) is
`journal_entry` (parent, one row per double-entry transaction) and `posting` (child rows, FK
`entry_id`, with a `direction` column and an *unsigned* `amount_minor` — not the signed column the
query assumes either). Confirmed directly against the running database:

```
$ docker compose -f docker-compose.infra.yml exec -T postgres psql -U ledger -d ledger -c "\dt"
 public | account_balance        | table
 public | journal_entry          | table
 public | ledger_account         | table
 public | posting                | table
 ...(no ledger_entry)
```

`gate.ps1`'s check 6 probes with `to_regclass('public.ledger_entry')`, gets `NULL` since the table
has never existed, and reports **"n/a: ledger_entry not reachable yet (pre-phase-02)"** — the exact
line this project has printed on *every* gate run since phase 02, including every one of the more
than eighty tagged tasks merged since then. The continuous balancing check that CLAUDE.md calls "an
invariant, not a preference" and the single thing "the entire project rests on" has not executed a
single time. `agent/gate.ps1` is on the protected list (`CLAUDE.md` rule 12: never edit
`agent/gate.ps1`), so this write-up is the fix's substitute until a human updates it by hand with
the corrected query at the top of this document. Every invariant check in the experiments below
used that corrected query directly against Postgres instead.

## 1. The idempotent-consumer wrapper silently defeats the retry chain

**This is the result that matters most from this phase.** Task 07.1 built a retry-topic chain
(`.retry-5s` → `.retry-1m` → `.retry-10m` → `.DLT`) specifically so a transient failure gets
retried instead of dropped. Task 00.6's `IdempotentHandler` (every consumer in the system goes
through it) marks an event processed *before* calling the delegate, in its own `REQUIRES_NEW`
transaction:

```java
// platform/common-messaging/.../idempotent/IdempotentHandler.java
public void handle(EventEnvelope<T> envelope) {
    if (!tryMarkProcessed(envelope)) {   // REQUIRES_NEW, commits immediately
        return;
    }
    delegate.handle(envelope);           // if this throws, the marker above is already committed
}
```

And `RetryTopicRelay` (07.1) republishes a failing message to the next retry topic with its
**payload byte-for-byte unchanged** — same `eventId`, same everything:

```java
// platform/common-messaging/.../spring/RetryTopicRelay.java
eventPublisher.publish(mainTopic, key, (String) record.value());
```

Put together: the first attempt marks the event processed, *then* the delegate throws (a chaos
`ledger.fail-rate` injection, or a process kill mid-handler — see below). The exception propagates
and the retry machinery dutifully republishes the exact same event to `.retry-5s`. Five seconds
later it comes back around to `IdempotentHandler.handle()`, which calls `tryMarkProcessed()` again,
gets a duplicate-key violation against the marker it already committed, and returns — **without
ever calling the delegate.** The retry chain doesn't retry the business logic at all; it just
delivers the event straight back to the one place designed to look at it and immediately do
nothing.

Once this happens, the event's fate is fixed forever. No exception is thrown (the second attempt
"succeeds" — successfully doing nothing), so it never reaches a DLT, never shows up as a metric,
and there is no scheduled sweep anywhere in `payment-service` or `ledger-service` for a payment
stuck in `PENDING`. The only surviving evidence is the mismatch between `processed_event` (has the
row) and `journal_entry`/`posting` (doesn't).

**Where this showed up:**

- **Experiment 1** (`ledger.delay=8000` then `docker kill ledger-service` ~1s in): payment
  `31acfd83-b6ef-4911-ae84-9709014b6462` never posted. `processed_event` shows it marked processed
  at `23:20:04.266` — under a second after the outbox published it, and long before the kill.
  `journal_entry` has nothing for it, then or ever. Restarting `ledger-service` redelivered the
  message (offset was never committed pre-crash) and logged a `duplicate key value violates
  unique constraint "processed_event_pkey"` WARN on the retry — confirming it hit exactly the
  path above. **The payment is stuck in `PENDING` permanently**, which directly contradicts the
  phase's predicted outcome ("Payment stays PENDING; completes on restart with no user action").
- **Experiment 4** (`ledger.fail-rate=30` under 12 concurrent transfers): 4 of 12
  (`experiment-4-4/5/8/9`) never resolved, 8 completed normally. All 4 stay `PENDING` after 80
  seconds — well past the `.retry-10m` chain's total window — with no `journal_entry` row and no
  DLT entry. This is the same bug, minus any process kill at all: an ordinary transient-failure
  injection under load reproduces it on its own.
- **Experiment 9** (scale `ledger-service` 1→3→1 under 20 concurrent transfers, one replica killed
  mid-burst): 19/20 completed; `experiment-9-scale-4`'s underlying payment
  (`917d2c86-a95e-49be-8677-cbd04d0a259a`) got stuck the same way, with no chaos lever involved —
  a real rebalance under real load hit the same window on its own.

No money moved in any of these cases (confirmed: `reservation` table stayed empty throughout,
`posting` and `account_balance` never reflect a partial effect), so the zero-sum invariant is never
actually at risk here. The cost is availability, not correctness: a user's transfer vanishes into
`PENDING` forever with nothing in any admin surface pointing at why.

**Recommended fix** (not applied — this is a cross-cutting change to a class every consumer in the
project depends on, and deserves its own deliberate task rather than a fix folded into an
experiments-and-writeup task): make the idempotency marker commit *in the same transaction* as the
delegate's work, or move it to *after* the delegate succeeds. Either removes the window where
"marked done" and "actually done" can diverge. The `REQUIRES_NEW` choice was almost certainly aimed
at a different problem (making the dedup-insert's own conflict handling independent of whatever
transaction semantics the delegate uses) without noticing it also uninverts the causality the retry
chain depends on.

## 2. The retry/DLT topic catalogue existed in code but not in this Kafka

**Experiment 3** (`poison payment.event.v1`): the poisoned message correctly triggered
`ErrorHandlingDeserializer` → `DeadLetterPublishingRecoverer`, which tried to publish to
`payment.event.v1.DLT` — and got `UNKNOWN_TOPIC_OR_PARTITION` in a tight retry loop, once a second,
forever:

```
[Producer clientId=ledger-service-producer-1] The metadata response from the cluster reported a
recoverable issue with correlation id 41 : {payment.event.v1.DLT=UNKNOWN_TOPIC_OR_PARTITION}
```

`infra/kafka/create-topics.sh` *does* define `payment.event.v1.DLT` (and the matching
`.retry-5s`/`.retry-1m`/`.retry-10m` topics) correctly — `retry_chain payment.event.v1` on line 62 —
but this Kafka broker (up for 4 days, since before task 07.1 landed) never had the script re-run
against it. The only DLT-shaped topic that existed was `card.authorization.v1-dlt`, an orphaned
leftover from some earlier, different naming convention, not part of the current catalogue at all.

**This is exactly the poison-pill outage the phase's own Retry topics section warns about** — and
worse than described, because `ledger-service`'s listener has no `concurrency` configured
(defaults to 1 thread for all 12 partitions of `payment.event.v1`). A single-partition stall would
have been bad enough; with one thread owning all twelve partitions, the whole topic stopped. A
completely unrelated transfer sent to a different partition sat in `PENDING` right alongside the
poisoned one (confirmed: partition 7's `LOG-END-OFFSET` advanced while `payment.event.v1-8`, the
poisoned partition, stayed at `CURRENT-OFFSET=-`/uncommitted — but nothing downstream of it moved
either, because the same thread services both).

**Fix applied:** ran `tasks.ps1 topics` (idempotent; creates every topic in the catalogue that
doesn't already exist). The stalled partition drained within seconds of the DLT topic existing;
the waiting transfer completed normally. No code change — this was a provisioning step that had
never been run against this particular long-lived broker, not a catalogue defect. **Operational
takeaway:** `infra/kafka/create-topics.sh` needs to be part of the startup/deploy checklist any time
the retry-chain catalogue changes, not just at first bring-up — a broker that predates a catalogue
change reproduces this outage on the very first poison message it ever sees.

The DLT admin endpoint (task 07.4) worked perfectly once the topic existed —
`GET /admin/dlt/payment.event.v1` returned the poisoned message with its full original topic,
partition, offset, exception class and stack trace, from both consumer groups that hit it
(`ledger-service` and `notification-service` both subscribe to `payment.event.v1`).

## 3. Cross-currency transfers were completely broken, independent of `fx.down`

**Experiment 7** (`fx.down`, expecting the breaker to open and cross-currency to fail fast while
same-currency stays unaffected) immediately hit an unrelated failure on the very first attempt —
*with `fx.down` never even applied yet*:

```
FX_QUOTE_UNAVAILABLE: fx-service unavailable for a cross-currency quote (IllegalArgumentException):
Not enough variable values available to expand 'FX_BASE_URL'
```

`docker-compose.yml` sets `FX_BASE_URL` for `simulator-service` and `card-service` (both call
fx-service too) but never for `payment-service` — the one service phase 04's cross-currency saga
actually depends on it for. With the variable entirely absent from the container's environment,
`FxClientProperties.baseUrl()` binds the literal unresolved text `${FX_BASE_URL}`, and
`RestClient`'s URI builder later chokes trying to expand `{FX_BASE_URL}` (the `{...}` substring
inside it) as a template variable with no value supplied. This is unconditional — it fails exactly
the same way whether fx-service is up, down, or doesn't exist.

**Fix applied:** added the missing `FX_BASE_URL: ${FX_BASE_URL:-http://fx-service:8085}` line to
`payment-service`'s environment block in `docker-compose.yml`, matching the identical pattern
already present for `simulator-service` and `card-service`. Confirmed fixed — the same request now
actually reaches fx-service.

That surfaced a **second**, unrelated problem: the request reached fx-service but got a genuine
`401 UNAUTHORIZED`, even with a valid, freshly-issued user JWT. Direct proof it's specific to the
relay path, not the token: the identical JWT sent straight to fx-service's
`POST /internal/fx/quotes` (bypassing payment-service entirely) succeeds with `201`. The most
likely explanation given the code: `FxQuoteRestClient.quote()` wraps the HTTP call in a Resilience4j
`CircuitBreaker.run(...)`, and Spring Cloud Circuit Breaker's Resilience4j integration pairs a
`CircuitBreaker` with a `TimeLimiter` by default — the `TimeLimiter` executes the protected call on
a *different* thread so it can enforce its timeout by cancelling the future. `currentBearerToken()`
reads `SecurityContextHolder`, which is `ThreadLocal`-based and does not follow the call onto that
other thread, so the relayed-token interceptor silently finds nothing to attach and the request
goes out unauthenticated. This is a known, general gotcha wherever Spring Security's thread-local
context meets a library that moves work to a different thread; it was not fixed here (it needs a
deliberate decision on the breaker's executor/context-propagation configuration, the same category
of cross-cutting change as §1), so cross-currency transfers still fail today, now with `422
FX_QUOTE_UNAVAILABLE` from the 401 rather than the URL-template crash.

**What still got confirmed, despite both bugs:** every cross-currency attempt — before the fix,
after the fix, with `fx.down` set or not — failed **fast** (single-digit milliseconds) with a clean
`422 Payment rejected / FX_QUOTE_UNAVAILABLE`, and never wrote a payment row (`PaymentInitiator`
rejects before either `persistCrossCurrency` or `persistSameCurrency` runs). A same-currency
transfer sent in the same window (`experiment-7-same`) completed normally. **The safety property
the experiment cares about most — a cross-currency failure never touches same-currency traffic —
holds**, even though the specific mechanism (circuit breaker opening on repeated `503`s) couldn't
be cleanly isolated from the two independent bugs masking it.

## The eleven experiments

| # | Experiment | Predicted | Actual |
|---|---|---|---|
| 1 | Kill `ledger-service` mid-transfer (`ledger.delay=8000`, kill ~1s in) | Stays PENDING; completes on restart | **Stuck in PENDING forever** — see §1. Invariant held (nothing partially posted). |
| 2 | Kill `payment-service` after the ledger posts (`outbox.pause` on ledger to create the window deterministically, kill payment-service, resume+restart) | Money already moved; result event consumed on restart, status resolves | **Exactly as predicted.** Balances moved instantly; payment stayed PENDING while paused/down; resolved to COMPLETED ~1 minute after restart with zero manual action. |
| 3 | Poison `payment.event.v1` | DLT within seconds; partition keeps flowing | **DLT topic didn't exist at all — the whole topic stalled**, not just the poisoned partition (single-threaded listener). Fixed by provisioning the missing topics (§2); recovered within seconds once the topic existed. |
| 4 | `ledger.fail-rate=30` under 12 concurrent transfers | Retries drain; zero lost payments; DLT empty | **4 of 12 silently lost** (stuck PENDING, never DLT'd) — see §1. DLT genuinely stayed empty (2 pre-existing entries from experiment 3, no growth) — the loss is invisible to DLT monitoring entirely. |
| 5 | Duplicate the last 100 `ledger.entry.v1` messages | Balances unchanged; `processed_event` absorbs all of them | **Exactly as predicted.** Balances identical before/after to the minor unit; zero DLT growth. (Unlike §1, these are duplicates of *successfully* processed events, not retries of failed ones — the dedup guard's actual intended case, and it works correctly there.) |
| 6 | `outbox.pause` (payment-service) for a window, then resume | Events accumulate in the DB, then drain in order; nothing lost | **Exactly as predicted.** 5 transfers sent while paused accumulated as unpublished outbox rows; all 5 drained and completed within ~8s of resuming; zero unpublished rows left. |
| 7 | `fx.down` | Breaker opens; cross-currency fails fast; same-currency unaffected | **Partially confirmed** — see §3. Two independent, unrelated bugs (one fixed, one documented) made the specific "breaker opens on 503" mechanism unobservable, but fail-fast and same-currency isolation both held in every configuration tried. |
| 8 | Kill Postgres for ~30s | Services report DOWN, recover on reconnect, no corruption | **Exactly as predicted.** Every service's `/actuator/health` flipped to DOWN (readiness probes timed out against HikariCP's connection-acquisition timeout, ~30s). All eight services reconnected on their own within ~10s of Postgres coming back, with no restart needed. A transfer sent immediately after recovery completed normally. Invariant held throughout. |
| 9 | Scale `ledger-service` 1→3→1 under load (20 concurrent transfers, one replica killed mid-burst) | Clean rebalance; ordering preserved; no duplicate postings | **Confirmed**, plus a natural instance of §1. 12 partitions split 4/4/4 on scale-up (`CooperativeStickyAssignor` in effect — logs show `protocol='cooperative-sticky'`); killing one replica rebalanced cleanly to 6/6 within the consumer session timeout; scaling back to 1 returned all 12 partitions to the sole survivor. 19/20 transfers completed; the 20th hit §1's bug on its own, with no chaos lever involved. Zero duplicate `journal_entry` rows for any `payment:*` key (checked directly). Invariant held throughout. **Could not be run via `docker compose up --scale`** — `ledger-service`'s `container_name` and static host port in `docker-compose.yml` both conflict across replicas, exactly the command the phase's own design section prescribes. Worked around by running two extra containers by hand on the same network/consumer group, `docker run --network revolut_default -e ... revolut-ledger-service:latest` — a real fix (dropping `container_name`/the fixed port, or moving to a `docker-compose.yml` shape that tolerates `--scale`) is a follow-up, not attempted here. |
| 10 | Rebuild the analytics projection from offset zero | Identical aggregates; zero notifications | **Exactly as predicted.** `tasks.ps1 analytics-rebuild` dropped and re-migrated the schema, reset both consumer groups to offset zero, and replayed: `ledger_entry_fact` count identical before/after (1063 = 1063), `monthly_spend` identical (both empty), and `notification` table's row count unchanged (117 = 117) — the replay touches only `analytics-service`'s own consumer group, so `notification-service` never re-fires. |
| 11 | Exhaust one service's resources (OOM) and map the blast radius | Documented blast radius; other features keep working | `docker update --memory=48m` reliably OOM-killed `notification-service` (`OOMKilled=true`, exit 137). **Blast radius was exactly as narrow as the design intends**: a transfer sent with `notification-service` completely dead still returned 202 and completed normally end to end; the gateway stayed healthy (it has no `depends_on` on notification-service, correctly). The one real surprise: **`docker-compose.yml` sets no restart policy on any service** (`RestartPolicy.Name` is the Docker default `no`), so the OOM-killed container stayed `Exited` indefinitely — nothing in this stack self-heals a crashed container the way Kubernetes would. That's an accurate property of a `docker-compose`-based dev environment, not a bug to fix here, but worth carrying into phase 09/10's production discussion explicitly. |

## Zero-sum invariant

Checked with the corrected query (§0) before, during and after every experiment above. It never
returned a row. The one place money could plausibly have gone missing — the payments silently
dropped in experiments 1, 4 and 9 — never got far enough to post anything at all (confirmed: no
`journal_entry` row exists for any of the five affected payment IDs, and the `reservation` table
stayed at zero rows throughout every experiment), so there is no partial or unbalanced effect to
find. The failures in this phase are availability failures — a transfer that should complete never
does — not correctness failures. Nothing here produced or could have produced an unbalanced
transaction.

## Summary of fixes applied during this phase vs. left as findings

| Issue | Where | Action |
|---|---|---|
| `ledger_entry`/`transaction_id` don't exist; gate's invariant check has never run | `CLAUDE.md`, `agent/gate.ps1` (protected, not editable) | **Documented only** (§0). Corrected query used for every check in this document. |
| Idempotency marker commits before the delegate; retries are silently swallowed | `platform/common-messaging/.../IdempotentHandler.java` | **Documented only** (§1) — cross-cutting fix affecting every consumer in the project; needs its own task. |
| Retry/DLT topics defined in the catalogue but never created on this broker | Kafka broker state, not code | **Fixed**: ran `tasks.ps1 topics`. |
| `FX_BASE_URL` missing from `payment-service`'s environment | `docker-compose.yml` | **Fixed**: added the line, matching the existing pattern on `simulator-service`/`card-service`. |
| Relayed bearer token lost across the circuit breaker's `TimeLimiter` thread | `services/payment-service/.../FxQuoteRestClient.java` (Resilience4j config) | **Documented only** (§3) — needs a deliberate decision on context propagation. |
| `ledger-service` cannot be scaled via `docker compose up --scale` as committed | `docker-compose.yml` (`container_name`, static port) | **Documented only** (§Experiment 9) — worked around with manual `docker run` for this session. |
| No restart policy on any service | `docker-compose.yml` | **Documented only** — accurate for a dev compose file; a production-orchestrator concern for phase 09/10. |
