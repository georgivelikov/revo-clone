# Phase 09 — Polish

**Effort:** 2–3 days · **Prerequisites:** [Phase 08](phase-08-load-and-observability.md) green · [← Index](00-overview.md)

## Goal

Someone clones the repo, runs one command, and three minutes later is clicking through a
working neobank with realistic data. The README explains what was built and why the
interesting decisions were made. This phase is what turns nine phases of work into something
you can show someone — or return to in six months without re-deriving everything.

## Deliverables

```
docker-compose.yml          one command, everything, healthchecked
tasks.ps1 / tasks.sh        up, down, reset, seed, demo, test, load
tools/seed/                 idempotent demo-data generator
README.md                   architecture, decisions, how to run
docs/
├── architecture.md         diagrams and the reasoning
├── decisions/ADR-0001..N   the choices worth recording
├── failure-modes.md        from Phase 07
└── performance.md          from Phase 08
.github/workflows/ci.yml    build + test on push
```

## Design detail

### One command

```powershell
docker compose up -d
```

Must bring up 10 services, 5 infra containers and the web app, in the right order, with
every service waiting on genuine readiness rather than a sleep. Requirements:

- **Healthchecks everywhere.** `depends_on: condition: service_healthy`, not
  `condition: service_started`. Kafka's healthcheck should verify the broker answers a
  metadata request, not just that the port is open.
- **Topic creation as a one-shot** container that runs after Kafka is healthy and exits 0.
- **Flyway migrations on startup**, each service owning its own schema.
- **Multi-stage Dockerfiles** — build with Maven, run on a JRE base. A layered Spring Boot
  image (`layertools`) keeps rebuilds fast because dependencies cache separately from code.
- **Total cold start under 3 minutes** on this machine. If it is longer, something is waiting
  on a timeout rather than a signal.

Keep `docker-compose.infra.yml` working in parallel — running services from the IDE against
containerised infra remains the right dev loop, and it will rot if it is not used.

### Cloud-readiness

The project runs locally and is meant to stay that way. This section is what keeps a later
move to AWS a deployment exercise rather than a rewrite. Most of it is already true if Phase
00's config rules were followed; this is the audit.

- **Liveness and readiness as distinct probes.** `/actuator/health/liveness` and
  `/readiness` — Spring Boot supports the split but it needs enabling. Readiness must go DOWN
  when Kafka partitions are unassigned or the DB pool is exhausted; otherwise an orchestrator
  routes traffic to an instance that cannot serve it and the failure looks like a bug in the
  caller. Liveness must *not* depend on downstream health, or a Kafka hiccup restarts every
  service at once.
- **Secrets from the environment, never baked into an image.** `.env` locally, Secrets
  Manager or SSM injected as env vars on AWS — same code path, different source. Nothing in
  `docker-compose.yml` should contain a credential that also exists in git.
- **Images tagged with the git SHA**, not `latest`, and built for an explicit platform
  (`linux/amd64`, or `arm64` if Graviton is likely). `latest` makes "which build is running"
  unanswerable the first time something breaks.
- **Graceful shutdown**, already built in Phase 07, is a cloud requirement and not just a dev
  nicety: ECS and Kubernetes send SIGTERM and wait a bounded time before killing the process.
- **Every service stateless.** True for all of them once Phase 07's Redis fan-out removes
  `notification-service`'s in-memory sessions. Verify it rather than assuming it: scale each
  service to 2 and confirm the system still behaves.

The point of this list is not to deploy anything. It is that each item is nearly free now and
genuinely annoying to retrofit across ten services later.

### Seed data

The demo is only as good as the data. `tasks.ps1 seed` should produce:

- **4 users** — Alice, Bob, Carol, Dan — each with EUR/GBP/USD wallets and plausible balances.
- **3 months of history**: ~150 card transactions across ~30 merchants with realistic
  category and amount distributions (many small groceries, a few large travel), a handful of
  P2P transfers between the four, several FX exchanges, two vaults with round-ups enabled and
  visible progress, and two budgets — one comfortable, one already breached so the amber
  state is visible on first load.
- **Timestamps spread realistically** — clustered on weekends and evenings, not uniform. A
  uniform distribution looks obviously synthetic in the trend chart.

Non-negotiable properties:

1. **Idempotent.** Run it twenty times, get the same state. You will run it twenty times.
2. **Goes through the real APIs.** No direct SQL inserts. Seeding through the public API
   means the seed script is itself an end-to-end test, and it cannot produce a state the
   application could not have reached. Slower, and worth it.
3. **Leaves the ledger balanced.** Assert the zero-sum invariant at the end of seeding.

Add `tasks.ps1 demo` — a scripted walkthrough that pauses between steps so you can narrate
it: fund, transfer, exchange, card spend, advance clock, settle, show the dashboard.

### README

The thing most people will actually read. Structure:

1. **What this is** — one paragraph, and an explicit "learning project, not production,
   no regulatory compliance" line up front.
2. **Screenshots** — four or five, at the top. Nothing else sells it as quickly.
3. **Quick start** — three commands, and what you should see.
4. **Architecture** — the service diagram, the event flow for one transfer, the port map.
5. **The interesting decisions** — six or eight short sections, each a paragraph:
   double-entry over mutable balances; outbox over dual writes; partition key = account id;
   three-layer idempotency; auth holds vs settlement; replayable projections; 202 over 201.
   Link each to its ADR.
6. **What I'd do differently** — the honest section, and the one that signals the most.
7. **Running the tests**, **load testing**, **failure experiments** — pointers to the docs.

Write section 5 for someone who has not read the code. Each decision needs the alternative,
the choice, and the consequence — in three sentences.

### ADRs

Short, dated, in `docs/decisions/`. One page each, with the standard shape: context,
decision, consequences (including the bad ones). The set worth writing:

| ADR | Decision |
|---|---|
| 0001 | Microservices with database-per-service over a modular monolith |
| 0002 | Double-entry ledger over mutable balance columns |
| 0003 | Transactional outbox over direct Kafka publishing |
| 0004 | Partition by account id |
| 0005 | Optimistic locking over pessimistic on balances |
| 0006 | Idempotency at three layers |
| 0007 | Keycloak over hand-rolled auth |
| 0008 | JSON over Avro/Schema Registry |
| 0009 | Auth holds modelled as reservations, not postings |
| 0010 | Analytics as a replayable projection |
| 0011 | Ports at three seams — and why the broker and dialect are deliberately not abstracted |

ADR 0008 is the one where the honest answer is "JSON was simpler and the tradeoff is no
enforced schema evolution" — write it that way. ADRs that only justify are worthless; the
consequences section is the whole value.

ADR 0011 is the other one worth writing carefully, because the interesting half is what was
*not* abstracted: the design depends on Kafka's log semantics and on Postgres-specific SQL,
and pretending otherwise would have meant designing to a lowest common denominator that
removed most of what the project set out to demonstrate. Record the swap-test results from
the verification section as the evidence.

### CI

`.github/workflows/ci.yml`:

```yaml
build:      mvn -B verify          # unit + Testcontainers, Docker available on runners
frontend:   npm ci && npm run build && npm run test
lint:       spotless:check, eslint
e2e:        docker compose up -d && playwright test     # optional, slow
```

Cache the Maven and npm directories or CI takes fifteen minutes. Testcontainers works on
GitHub-hosted runners without special setup; the E2E job is the one that may be too slow to
run on every push — schedule it nightly instead.

Add a status badge to the README. Also add a CI step asserting the zero-sum invariant after
the E2E run — a build that goes red when money leaks is a satisfying thing to own.

### Frontend polish

The gap between "works" and "looks intentional" is mostly small things:

- **Loading states** — skeletons, not spinners, on every list and balance.
- **Empty states** — every screen with no data needs a designed empty state.
- **Error states** — a failed transfer shows the reason and a retry, never a raw stack.
- **Offline** — detect a dropped socket and show a subtle reconnecting indicator.
- **Transitions** — the balance number should count up, not jump; sheets should spring.
- **Accessibility** — focus rings, keyboard-navigable flows, labelled controls, and a
  contrast pass on the dark theme. The gradient card faces are the likely failure.
- **Responsive** — it is a 430 px column, but it must not break at 320 px or look abandoned
  on a 4K display.

## Task list

1. Multi-stage layered Dockerfiles for all 10 services + the web app.
2. Full `docker-compose.yml` with real healthchecks and ordering.
3. Verify cold start under 3 minutes; fix whatever is waiting on a timeout.
4. Seed generator via public APIs, idempotent, with realistic distributions.
5. `tasks.ps1 demo` scripted walkthrough.
6. Frontend polish pass: loading, empty, error, offline, motion, a11y.
7. Screenshots for the README.
8. Write the README.
9. Write the ten ADRs.
10. `docs/architecture.md` with the service diagram and a transfer sequence diagram.
11. CI workflow with caching, badge, and the invariant check.
12. Cross-platform `tasks.sh` alongside `tasks.ps1`.

## Verification

The real test — do this on a clean machine, or at least after `docker system prune -a`:

```powershell
git clone <repo> && cd Revolut
docker compose up -d          # under 3 minutes
.\tasks.ps1 seed              # under 60 seconds
# open http://localhost:5173, log in as alice@demo.io / demo
```

Then check, as a stranger would: is the dashboard populated and plausible? Does every screen
have something to show? Does the demo script run start to finish without a manual fix? Can
someone who has never seen the repo follow the README's quick start without asking a
question?

### Prove the portability boundaries

The index claims three layers sit behind seams. That claim is cheap to test and worthless
untested — a diagram of interfaces proves nothing. Each of these is about an hour:

1. **Swap the broker.** Point `docker-compose.infra.yml` at Redpanda instead of Kafka and run
   the full suite. Green means the Kafka-protocol boundary is real. This is the single best
   piece of evidence the abstraction holds, because Redpanda is a genuinely different
   implementation of the same protocol.
2. **Exercise the identity port.** Run the suite against `InMemoryIdentityAdapter`, then
   against Keycloak, from the same `IdentityProviderPortContractTest`. Both green means the
   port is a boundary and not a pass-through with extra steps.
3. **Swap the database endpoint.** Point at a Postgres in a separate container with TLS
   required and a non-superuser role. This catches the managed-Postgres constraints on your
   laptop instead of during a first deploy.
4. **Audit the config.** `grep -ri localhost` across committed files — every hit must be
   inside a `local` profile.

Record the results in `docs/architecture.md`. "We swapped the broker and the suite passed" is
a far stronger statement than "the messaging layer is abstracted", and it is the kind of
claim worth being able to make.

## Done when

- [ ] `docker compose up -d` works from a clean state in under 3 minutes
- [ ] Seed is idempotent, goes through public APIs, and leaves the ledger balanced
- [ ] Liveness and readiness are separate probes, and readiness reflects Kafka and DB health
- [ ] No credential in any committed file; images tagged with the git SHA
- [ ] Every service runs at 2 replicas without misbehaving
- [ ] The full suite passes against Redpanda as well as Kafka
- [ ] The full suite passes against both identity adapters
- [ ] No `localhost` outside a `local` profile
- [ ] Every screen has designed loading, empty and error states
- [ ] README has screenshots, quick start, architecture and the decisions section
- [ ] Ten ADRs written, each with an honest consequences section
- [ ] CI green, with a badge and an invariant check
- [ ] The demo script runs end to end unattended
- [ ] A contrast and keyboard-navigation pass is done
- [ ] `docker-compose.infra.yml` still works for IDE development

## Risks & gotchas

- **This phase is easy to skip and expensive to skip.** Nine phases of good engineering that
  nobody can run is worth very little. Timebox it, but do it.
- **Healthcheck ordering is the main time sink.** Kafka in KRaft mode reports the port open
  well before it accepts metadata requests; a naive check lets dependents start too early and
  fail in a way that looks like a code bug.
- **Seeding through public APIs is slow** because of the async saga — the seed script must
  wait for `COMPLETED` rather than firing and hoping. Poll, with a timeout and a clear error.
- **Screenshots go stale.** Take them last, and regenerate them if the UI changes.
- **Polish creep.** "One more animation" can absorb a week. The list above is the scope; new
  ideas go in a backlog, not into this phase.
- **Don't write ADRs that only justify.** If you would do it differently now, the ADR should
  say so — that is the most useful thing in the document.
