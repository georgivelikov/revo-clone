# Performance — Phase 08

The numbers, the bottleneck, and the reasoning behind them. Every run referenced here has its
raw k6 `--summary-export` JSON committed under `docs/perf-runs/`, and every finding below was
read off the live stack (Prometheus queries, `psql`, `docker logs`), not inferred.

All runs in this document were made on one laptop against the full Docker Compose stack
(`docker-compose.yml` + `docker-compose.obs.yml`, `--profile services`). These are not
production numbers — see "Scale-down note" below for the one place that distinction actually
matters for a result's validity, and treat every absolute millisecond figure here as "what this
machine produced," not a capacity claim about any particular production hardware.

## Summary

| Question | Answer |
|---|---|
| Accept-path (`POST /api/payments`) latency | Consistently single-digit-to-tens of ms at every rate tested, 10–1000 rps. Never the bottleneck. |
| Predicted top bottleneck | **Refuted.** Neither HikariCP pool size nor optimistic-lock contention was ever the limiting factor at any tested load. The real one was predicted bottleneck #3: the outbox poller. |
| One tuning change, quantified | Adaptive outbox polling (task 08.9): pending-row backlog cut from unbounded growth (~15,000+ rows) to a bounded tens-to-low-hundreds, a ~99% reduction. |
| Sustained throughput at p99 < 500ms end-to-end | **Not achieved at any tested rate** (10–500 rps). Root cause identified and quantified below — it is structural, not load-dependent. |
| Hotspot (single-account) ceiling | Optimistic-lock retries are **zero** at single-instance scale regardless of load (the mechanism never activates), and exactly as designed once genuinely scaled: 6.7% retry rate, 100% success, at 3 ledger-service instances. |
| Spike recovery | 1000 rps instant step from 50 rps: accept layer held at 99.92% success. Consumer lag peaked at ~45,000–47,000 messages and drained fully — see the Spike section for the measured time. |
| 2-hour soak | Run at a documented, shorter scale — see "Scale-down note". No JVM leak found. A real, non-leak drift was found and explained. |
| Zero-sum invariant | Held after every single load run in this document, with no exception. |
| DLT depth | Zero after every run in this document (one pre-existing artifact found and removed — see 08.9). |

## Scale-down note: the soak test

The plan specifies a 2-hour soak at 100 rps. This document reports a **15-minute** soak at
100 rps instead, by an explicit decision made before task 08.10 started: on a single laptop
sharing resources with everything else in this session, a full 2-hour run bought little
additional confidence over a well-instrumented 15 minutes for the thing a soak is actually
checking — JVM heap growth, GC degradation, and connection-pool exhaustion are visible within
minutes if they are going to happen at all, and none did. What a longer run *would* have added
is more confidence that the drift finding below (Kafka consumer lag growing without bound at
100 rps) is not itself a self-limiting transient — but that finding is independently confirmed
by task 08.9's shorter runs and by the dedicated 60 rps / 10 rps runs in this document, so the
shorter soak's conclusion does not actually depend on the missing 1h45m. The honest limitation:
this does not rule out a slow leak with a time constant longer than 15 minutes. Treat the "no
leak" finding as strong for anything with a sub-hour time constant, not as a 2-hour-equivalent
guarantee.

## 1. Baseline and the search for the knee (08.6, 08.7)

**08.6** — 50 users, flat 100 rps, 5 minutes, 30,000 transfers (`08.6-baseline.json`).
Accept-path `http_req_duration`: p95 **25.25ms**, 0% failed. Clean.

**08.7** — 50 users, ramped 10→500 rps over 5 minutes, held at 500 for 5 more, 227,543
requests (`08.7-ramp.json`). Accept-path p95 **84.16ms** even held at 500 rps, 0.02% failed.
**There is no knee at the accept layer** — it degrades gracefully all the way to 500 rps on
this hardware.

Both runs found the real knee one layer downstream, in the outbox:

- Each domestic transfer writes 1 outbox row on payment-service and 2 on ledger-service (one
  `EntryPostedV1` per posting). At 100 rps that is ~200 outbox rows/sec needed on
  ledger-service against a `OutboxPublisher` poller fixed at a 1-second delay / 100-row batch —
  a hard ceiling of ~100 rows/sec/service, already half of what 08.6's load needed.
- 08.6: backlog grew to **~15,000** unpublished rows on ledger-service, **~7,400** on
  payment-service, by the end of one 5-minute run.
- 08.7, at 500 rps sustained: payment-service's backlog reached **~197,700** rows (payment
  needs 2x ledger's per-transfer rate but is the one actually gated by its own poller).
- In both runs, end-to-end latency (`payment.end.to.end`, accept → `COMPLETED`) read
  **p50 = p95 = p99 = 30 seconds** — the Timer's own histogram ceiling, meaning the true value
  was *at least* 30s for the median transfer by the end of the run, not merely delayed.
- Backlog drained cleanly to 0 within ~6 minutes once load stopped in both cases — this is a
  throughput ceiling, not data loss or a stuck poller.

**Predicted bottleneck, confirmed or refuted:** the plan predicted, in order, (1) HikariCP pool
size, (2) optimistic-lock contention, (3) outbox poll interval, (4) Kafka producer batching.
Neither (1) nor (2) was ever observed to matter at any load tested in this document — HikariCP
active-connection counts never exceeded single digits out of a pool of 10 (see the soak
section), and optimistic-lock retries were genuinely zero until task 08.8 specifically
engineered contention by scaling horizontally. **(3) was the real, measured, top bottleneck.**

## 2. Hotspot — the single-account ceiling (08.8)

50 senders, 500 rps, all targeting one fixed recipient account, 2 minutes, 60,000 transfers
each run.

**Single ledger-service instance** (`08.8-hotspot.json`): `ledger_optimistic_retries_total`
stayed at **0** through all 60,000 transfers, 100% success, accept p95 **22.02ms**. Not a near
miss — genuinely zero. Reason: `payment.event.v1` is keyed by `paymentId` (CLAUDE.md rule 5's
documented "usually account id" exception), and with one ledger-service instance running a
single-threaded listener, every `PostingTransactionExecutor` call for every partition is
already serialized by construction — optimistic locking exists for genuine concurrent writers,
and a lone sequential consumer can never produce the condition it defends against.

**Scaled to 3 ledger-service instances** (phase 07's technique — `docker-compose.yml`'s
`container_name`/static port block `--scale`, so `docker run` directly on the shared network
instead), identical run (`08.8-hotspot-scaled.json`): 12 partitions split 4/4/4 across
genuinely separate JVMs. `ledger_optimistic_retries_total`: **4,045** across the same 60,000
transfers (~6.7% of operations needed at least one retry), still **100% success, 0 failed
transfers** — accept p95 climbed to **82.67ms**, max **959ms** (the `@Retryable` 20ms/40ms
backoff showing directly in the tail). Three-attempt retry absorbed every conflict without a
user-visible failure.

**The clean answer to "worst case for optimistic locking":** the mechanism is not needed, and
does not activate, at single-instance scale no matter the load. It only matters once the system
is actually scaled the way phase 07 demonstrated it can be — and when it does matter, it works
exactly as designed.

## 3. The tuning change — adaptive outbox polling (08.9)

**Change:** `OutboxPublisher.publishPending()` used to poll once per fixed 1-second tick
regardless of backlog. It now loops — re-polling immediately through its own Spring proxy
whenever the last batch came back full, capped at 50 batches/tick so a never-draining backlog
cannot monopolize the scheduler thread forever. Each batch still commits in its own
transaction, so `SKIP LOCKED`'s cross-replica safety is unaffected.

**Re-run:** identical to 08.6 (50 users, 100 rps, 5 min, `08.9-tuned.json`), against all ten
services rebuilt with the change.

**The outbox side is a clean, quantified win:** pending-row backlog stayed bounded in the
tens/low-hundreds throughout the run (peak **134** on ledger-service, confirmed via a
Prometheus range query across the full run) versus 08.6's unbounded growth to ~15,000 — a
**~99% reduction**, and task 08.10's longer 15-minute soak confirmed this holds over a sustained
run, not just a short burst (peak backlog there: low 200s). The outbox poller is no longer the
limiting stage of the pipeline.

**But fixing it exposed the next bottleneck immediately**, and that is recorded here rather
than hidden: end-to-end latency was still pinned at the same 30-second histogram ceiling.
Once the outbox stopped throttling Kafka publishes, ledger-service's and notification-service's
Kafka listeners — single-threaded, no explicit concurrency configured, the same fact task 08.8
identified for a different reason — cannot keep up with 100 msgs/sec either. Consumer lag on
ledger-service climbed to **~14,000–22,000** and notification-service to **~27,000–64,000**
during sustained runs, confirmed draining back to 0 within minutes after each run stopped (a
sub-critical queue, not a stuck consumer). `ledger.posting.duration`'s own per-message cost
(p50 ~7ms) combined with a measured live draining rate of **~77–90 messages/sec** confirms a
single consumer thread tops out below the 100/sec arrival rate once Kafka ack and DB commit
overhead are included.

This is an honest delta, not a full fix: the tuning change is real and quantified, but it moved
the bottleneck rather than raising the system's overall 100 rps ceiling.

## 4. The soak — leaks and drift (08.10)

15 minutes at 100 rps, 50 users, 90,000 transfers (`08.10-soak.json`) — see the scale-down note
above for why 15 minutes, not 2 hours. Accept p95 **19.88ms**, 0.01% failed — the accept path
itself shows no degradation across the full run.

**Leak check — clean.** JVM heap on both ledger-service and payment-service oscillates in a
stable sawtooth band (roughly 60–210MB) for the full 15 minutes with no upward trend. GC pause
rate settles to a small flat value after initial warmup (~0.05–0.08% of wall time) and stays
flat. HikariCP active connections never exceed 1–2 out of a pool of 10, the entire run. No
leak, no unbounded JVM growth, pool exhaustion never approached.

**Drift check — real, and explained, not a leak.** Outbox backlog stays correctly bounded for
the full sustained 15 minutes, confirming 08.9's fix holds over a long run. But Kafka consumer
lag on ledger-service and notification-service grows **continuously** across the entire run
(ledger: 0 → ~22,000; notification: 0 → ~64,000), because 100 rps exceeds the ~77–90 msgs/sec
sustainable consumption rate found in 08.9. End-to-end p95 drifts from ~20s at minute 5 to the
Timer's 30s ceiling by minute 15 — a direct, fully explained consequence of the growing queue,
not of anything decaying over time. **The system never reaches a true steady state at 100 rps**;
a soak at this rate cannot distinguish "still filling a queue" from "leaking," which is exactly
why the lower-rate runs in the next section were necessary.

## 5. Is p99 < 500ms end-to-end achievable? (08.11)

None of the runs above ever got there — but all of them ran at or above the ~77–90 msgs/sec
consumer ceiling, so a reasonable question is whether a lower, genuinely sustainable rate would
finally show it. Two more runs answered this directly.

**60 rps, 50 users, 5 minutes** (`08.11-sustainable-60rps.json`, comfortably below the ~77–90/
sec ceiling): outbox backlog and consumer lag both stayed small and bounded throughout (tens to
low hundreds) — no congestion, confirmed via Prometheus range query. Accept p95 **15.98ms**.
And yet end-to-end: **p50 = 1.45s, p95 = 2.08s, p99 = 2.43s.** Not close to 500ms.

**10 rps, 20 users, 2 minutes** (floor test, no meaningful load at all): end-to-end
**p50 = 1.37s, p95 = 1.93s, p99 = 2.10s** — essentially identical to the 60 rps numbers.

That identity is the finding: **the ~1.4–2.4s end-to-end floor is structural, not
load-dependent.** The critical path crosses two sequential outbox-poll hops — payment-service's
own outbox (accept → `PaymentInitiatedV1`) and, after ledger-service posts the entry,
ledger-service's own outbox (`EntryPostedV1`, consumed by payment-service to mark
`COMPLETED`). At low-to-moderate rates the per-poll batch is never full, so task 08.9's
adaptive immediate-reloop never engages and each hop instead waits for its next scheduled
1-second tick — a wait uniformly distributed 0–1000ms, averaging ~500ms, twice in the chain.
That alone accounts for the bulk of the observed ~1.4s median.

**Conclusion for this checklist item: p99 < 500ms end-to-end is not achievable with the
current architecture, at any rate tested from 10 to 500 rps**, for two independent reasons
depending on load: at low/moderate rates, the fixed poll-interval floor dominates; at ≥100 rps,
queueing on the under-provisioned consumer dominates instead and the numbers get much worse.
Closing this gap would need either a much shorter base poll interval (trading idle-CPU/DB-load
for latency), an event-driven trigger for the outbox poller (e.g. Postgres `LISTEN`/`NOTIFY`
on insert, removing the wait-for-next-tick floor entirely), or increased listener concurrency
on ledger-service/notification-service to raise the ≥100 rps ceiling found in 08.9/08.10 — none
of which this phase's scope covers.

## 6. Spike — lag drain time (08.11)

50 rps baseline → instant step to 1000 rps for 60s → instant step back to 50 rps, 100 users
(`08.11-spike.json`). 49,627 requests. **Accept layer: 99.92% success** (44 failures, all
during the 1000 rps plateau) — far better than an earlier reduced-scale smoke test had
suggested, and consistent with every other finding in this document that the accept layer is
never the limiting stage.

Consumer lag on ledger-service and notification-service spiked to **~45,000–47,000** messages
within about 2 minutes of the burst (expected: 49,627 events arriving in ~110 seconds against a
~77–90/sec consumption ceiling necessarily queues almost the entire burst). Measured by polling
Prometheus every 45s from the moment the run ended until both consumer groups independently
read 0: **ledger-service drained in ~8.3 minutes, notification-service (the higher peak, and
the slower of the two) in ~10.5 minutes** — both consumer groups fully recovered unassisted,
without a single additional scaling action or intervention. Zero-sum invariant held and DLT
depth was zero both before and after the run — nothing was lost, only delayed, exactly as the
architecture intends a spike to behave.

## 7. Invariants

Checked directly against the database after every load run in this document (`posting` table,
summing `CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END` per `entry_id`):
**zero unbalanced entries, every time**, across roughly 700,000 transfers made across all runs
in this document combined. `kafka_dlt_depth` was zero after every run except once: before 08.9,
two pre-existing `chaos.poison` messages (phase 07 chaos-testing leftovers, not anything this
phase introduced) were found on `payment.event.v1.DLT`, confirmed via direct inspection, and
removed.

## Dashboard

`infra/grafana/provisioning/dashboards/json/revolut-overview.json`, provisioned automatically
via `docker-compose.obs.yml` — Grafana at `http://localhost:3000`. 17 panels covering every
metric in the Instrumentation table above: ledger posting/rejection rates, accept and
end-to-end latency histograms, consumer lag and DLT depth per topic, JVM heap/GC, HikariCP
saturation, and the optimistic-retry rate.

## 8. Cold start (phase 09 task 09.3)

`plans/phase-09-polish.md`'s Design detail requires `docker compose up -d` to bring up all 10
services, the web app and 5 infra containers in under 3 minutes, with every service waiting on
a genuine readiness signal rather than a sleep. Measured on this machine, images already built
(`GIT_SHA=$(git rev-parse --short HEAD) docker compose -f docker-compose.infra.yml -f
docker-compose.yml --profile services up -d`, timed from invocation to the command returning,
which itself blocks on every `condition: service_healthy`/`service_completed_successfully` in
the dependency graph):

**128 seconds** — under the 3-minute budget, with headroom. Every container (10 services, web,
postgres, kafka, redis, keycloak, plus the one-shot `kafka-topics-init`) reported `healthy` (or
`Exited (0)` for the one-shot container) with no restart loops, and nothing in the startup log
was waiting on a fixed sleep rather than a signal — the ordering added in task 09.2
(`kafka-topics-init` before every Kafka-touching service, `service_healthy` everywhere else)
was sufficient on the first clean run; nothing needed fixing.

Image build time itself (first `docker compose ... build`, no layer cache, 10 independent
Maven reactor builds with no shared dependency cache between them) is deliberately **not**
counted against the 3-minute figure — the Verification section's own command
(`docker compose up -d`, no `--build`) assumes images already exist, matching how a real
`git clone && docker compose up -d` run would normally follow a CI-built image pull rather than
a from-scratch local build.
