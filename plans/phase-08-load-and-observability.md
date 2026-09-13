# Phase 08 — Load and Observability

**Effort:** 2–3 days · **Prerequisites:** [Phase 07](phase-07-resilience-and-scale.md) green · [← Index](00-overview.md)

## Goal

Stop guessing. Put real load through the system, measure where it bends, and build enough
observability to explain *why*. The deliverable is a set of numbers you can defend and a
dashboard that makes a bottleneck visible in under a minute.

## Deliverables

```
tools/k6/               transfers.js, mixed.js, spike.js, soak.js
docker-compose.obs.yml  Prometheus + Grafana (+ Tempo, optional)
infra/grafana/          provisioned dashboards as JSON
docs/performance.md     the numbers, the bottleneck, the reasoning
```

## Design detail

### What to measure

Latency percentiles, not averages — an average hides the tail that users actually feel.
Track p50 / p95 / p99 for:

| Metric | Why |
|---|---|
| `POST /api/payments` (accept) | The synchronous part the user waits on |
| Accept → `COMPLETED` (end-to-end) | The part the user *perceives*, via the WebSocket |
| Ledger posting handler | The core transaction |
| Outbox lag (append → published) | Whether the poller keeps up |
| Consumer lag per group | The canonical scaling signal |
| Optimistic-lock retry rate | Contention — the ledger's main scaling limit |
| DLT depth | Must be zero |

The gap between the first two is the whole architectural bet: accept should be single-digit
milliseconds because it is one insert plus one outbox row, while end-to-end is tens or
hundreds of milliseconds. That gap is what asynchrony buys, and quantifying it is the point.

### Scenarios

| Script | Shape | Question it answers |
|---|---|---|
| `transfers.js` | Ramp 10 → 500 rps over 5 min, hold 5 min | Steady-state throughput and where p99 breaks |
| `mixed.js` | 60% transfers, 20% FX, 15% card auth, 5% reads | Realistic contention across services |
| `spike.js` | 50 rps → 1000 rps instantly, 60 s, back down | Lag spike and drain time |
| `soak.js` | 100 rps for 2 hours | Leaks, unbounded growth, slow drift |
| `hotspot.js` | 500 rps all targeting **one** account | Worst case for optimistic locking |

`hotspot.js` matters most. Keying by account id gives per-account ordering, and the price is
that a single hot account is a serialisation point. Measuring that price — and watching the
retry counter climb — is the concrete lesson behind the Phase-00 partitioning decision.

### Instrumentation

Micrometer + Actuator on every service, scraped by Prometheus. Custom metrics worth adding:

```java
Counter  ledger.entries.posted{type}
Counter  ledger.entries.rejected{reason}
Counter  ledger.optimistic.retries
Timer    ledger.posting.duration
Timer    payment.end.to.end            // started on accept, stopped on terminal state
Gauge    outbox.pending.count
Gauge    outbox.oldest.age.seconds     // more actionable than count
Counter  kafka.dlt.messages{topic}
Gauge    fx.quote.active.count
```

`outbox.oldest.age.seconds` is the one to alert on: a growing count might just be throughput,
but an old row means the poller is genuinely stuck.

Kafka consumer lag comes from the broker rather than the app — `kafka-exporter` scraped by
Prometheus gives lag per group per partition, which is what you actually want during a
rebalance.

Lag is a broker-side metric, so it works identically against MSK or any other Kafka-protocol
broker later — and it is the signal KEDA autoscales on in Phase 10's Kubernetes item. That is
worth knowing now, because it means the dashboard panel you build here is the same one that
drives scaling decisions later, rather than a laptop-only diagnostic.

### Dashboard

One Grafana dashboard, four rows, provisioned as JSON in the repo so it survives a rebuild:

1. **Business** — payments/sec by status, FX volume, card auth approve/decline ratio.
2. **Latency** — p50/p95/p99 for accept and end-to-end, as time series.
3. **Kafka** — consumer lag per group, messages/sec per topic, DLT depth.
4. **Resources** — JVM heap, GC pause, DB connection pool utilisation, optimistic retries.

Follow the `dataviz` guidance: consistent colour per service across every panel, so the eye
tracks one service down the page.

### Expected bottleneck

Predict before measuring, then compare. The likely order:

1. **Postgres connection pool** on `ledger-service` — HikariCP default is 10, which is low for
   500 rps. Tune it, and note that pool size should relate to CPU cores rather than being
   maximised.
2. **Optimistic-lock contention** on hot accounts — visible as retries, not errors.
3. **Outbox poll interval** — a fixed 500 ms poll caps end-to-end latency at ~500 ms
   regardless of everything else. Adaptive polling (poll again immediately if the last batch
   was full) is a cheap, large win.
4. **Kafka producer batching** — `linger.ms` trades latency for throughput; worth measuring
   both settings rather than accepting the default.

Being wrong about this prediction is a more useful outcome than being right.

### Optional: distributed tracing

OpenTelemetry + Tempo, with trace context propagated through Kafka headers so one transfer
produces a single trace spanning gateway → payment → Kafka → ledger → Kafka → notification.
Seeing that waterfall once explains more about the system than any diagram.

It is genuinely optional — roughly a day, and the correlation-id logging from Phase 00 already
covers most debugging needs. Do it if tracing is something you want to learn; skip it if the
goal is throughput numbers.

## Task list

1. `docker-compose.obs.yml` — Prometheus, Grafana, kafka-exporter, postgres-exporter.
2. Actuator + Micrometer + Prometheus registry on every service.
3. Custom metrics above, with consistent naming.
4. Grafana dashboard JSON, provisioned.
5. k6 scripts, with a shared setup module that registers and funds test users.
6. Baseline run at 100 rps; record it before tuning anything.
7. Ramp run to find the knee; record p99 at each step.
8. `hotspot.js` — measure the single-account ceiling.
9. Tune the top bottleneck; re-run; record the delta.
10. 2-hour soak; check for leaks and drift.
11. Write `docs/performance.md`.

## Verification

```powershell
docker compose -f docker-compose.yml -f docker-compose.obs.yml up -d
k6 run tools/k6/transfers.js
# Grafana at http://localhost:3000
```

During the ramp, watch: consumer lag rise and drain, the optimistic-retry counter climb as
contention increases, and outbox oldest-age stay flat. After the run, verify the invariant
that makes the numbers trustworthy:

```sql
select currency,
       sum(case when direction='CREDIT' then amount_minor else -amount_minor end)
from posting group by currency;   -- still exactly 0, after ~150k postings
```

A throughput number from a run that lost money is worthless. Check this every time.

## Done when

- [ ] Documented sustained throughput at p99 < 500 ms end-to-end
- [ ] Spike test: lag drains within a documented time, nothing lost
- [ ] Hotspot ceiling measured and explained
- [ ] The predicted bottleneck confirmed or refuted, with data
- [ ] One tuning change made and its effect quantified
- [ ] 2-hour soak with flat memory and no unbounded growth
- [ ] Zero-sum invariant holds after every load run
- [ ] DLT empty after every load run
- [ ] Dashboard provisioned in the repo, not just in Grafana's local state
- [ ] `docs/performance.md` written with numbers, method and caveats

## Risks & gotchas

- **k6 on the same machine competes for CPU** with 10 JVMs. With 14 cores there is room, but
  pin k6's VU count modestly and note in the write-up that client and server share hardware.
- **JIT warm-up** makes the first 30 s of every run meaningless. Add a warm-up stage and
  discard it, or the ramp results are nonsense.
- **Testcontainers and load runs compete for Docker.** Don't run `mvn verify` during a k6 run.
- **`docker stats` is not a profiler.** If a service looks slow, use the actuator metrics and
  JFR rather than guessing from container CPU.
- **Don't tune more than one thing at a time.** The temptation after finding the knee is to
  change four settings at once; then you learn nothing about which one mattered.
- **Numbers on a laptop are not production numbers.** Say so explicitly in the write-up. The
  value is the relative comparison and the shape of the curve, not the absolute figure.
