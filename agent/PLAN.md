# Autonomous build harness for the Revolut clone

## Context

`plans/` contains eleven phase documents and `docs/architecture.md` describing a
Revolut-style neobank: Java 25 / Spring Boot 4.1.1, ten microservices, Kafka, double-entry
ledger, sagas, React 19 frontend. Total estimated effort is **37–52 focused days** across
phases 00–09.

The plans are already agent-ready in a way most specs are not. Every phase file has the same
shape — Goal, Deliverables, Design detail, **Task list**, **Tests**, **Verification**,
**Done when**, Risks. That is precisely the contract an autonomous agent needs: an ordered
task list to execute and a machine-checkable exit condition to stop on.

The chosen operating mode is: **agents build the system end to end, merging their own work
when gates pass, on the local Windows machine.** The human reviews the result, not the
process. The purpose of this document is to build the *harness* that makes that safe and
resumable — not to write any service code.

The one thing that mode cannot survive is a gate that an agent can satisfy by cheating.
An agent that has failed `mvn verify` twice will, on the third attempt, consider deleting
the test. The design below spends most of its weight on making that impossible rather than
on orchestration cleverness, because orchestration is the easy half.

## Tooling decision

**Use Claude Code itself as the harness.** Headless mode (`claude -p`) driven by a
PowerShell loop, with hooks, subagents and skills for the repeatable parts.

Why not the alternatives:

| Option | Verdict |
|---|---|
| **Claude Code headless + hooks** | **Chosen.** Hooks are the only mechanism that can *deterministically block* an agent from stopping on red — that is the whole safety story for unattended merge. Native Windows, native PowerShell, already installed. |
| [Claw Code](https://a2a-mcp.org/blog/what-is-claw-code) | Clean-room Python/Rust reimplementation of Claude Code's architecture, provider-agnostic. Genuinely interesting, but it is a *younger harness*. For a 40-day unattended run you want the harness to be the boring part; debugging harness bugs at 3am is not distributed-systems practice. Revisit only if multi-provider becomes a requirement. |
| [claw-orchestrator](https://github.com/Enderfga/claw-orchestrator) | Runs Claude Code / Codex / Cursor as one runtime with multi-agent councils. Solves a problem this project does not have (single provider, sequential phases). |
| Worktree parallel runners (Claude Squad, conductor, vibe-kanban) | Ruled out by the local-machine choice: the stack binds fixed ports (5432, 9092, 8080–8089) so parallel worktrees each need a port offset scheme. Phases compound anyway — 02's ledger poisons everything after it if wrong — so sequential is correct. |
| [Claude Agent SDK](https://docs.claude.com/en/api/agent-sdk/overview) | A reasonable substitute for `run.ps1` if you want the driver in TypeScript with structured results instead of a shell loop. Same architecture, more code. Start with PowerShell; port later only if the loop gets complicated. |

**Model split:** Opus for `common-money`, the ledger (Phase 02), the saga (03a), FX (04) and
card auth/settlement (05). Sonnet for scaffolding, DTOs, Docker/Flyway/config, the React UI,
and Phases 08–09. Set per-task via `--model` from the task ledger.

## The core idea

40 days of work cannot live in one context window. The design is therefore
**process-level**: state lives in files, and each task gets a **fresh `claude -p` process**.
Compaction drift — the failure mode where an agent 200k tokens deep forgets that money is a
`long` — never happens, because no process lives that long.

```
agent/state.json  ──►  run.ps1  ──►  claude -p (fresh ctx, one task)
      ▲                                      │
      │                                      ▼
      │                              Stop hook ──► gate.ps1
      │                                      │
      └──── status: done / blocked ◄─────────┘ (red ⇒ block stop, keep working)
                                             │
                                          green ⇒ merge branch → master, tag
```

## Deliverables

```
Revolut/
├── CLAUDE.md                       # the project constitution — highest-leverage file here
├── .claude/
│   ├── settings.json               # permissions allowlist + denylist + hooks
│   ├── agents/
│   │   ├── implementer.md          # writes code for one task item
│   │   ├── verifier.md             # runs Verification block; read-only, cannot edit
│   │   └── phase-reviewer.md       # audits a finished phase against "Done when"
│   └── skills/
│       ├── new-service/SKILL.md    # scaffold a Spring Boot service to conventions (used 9×)
│       └── phase-verify/SKILL.md   # execute a phase's Verification section verbatim
└── agent/
    ├── state.json                  # the task ledger — source of truth for progress
    ├── run.ps1                     # driver loop
    ├── gate.ps1                    # the only thing authorised to declare a task done
    ├── tripwire.ps1                # forbidden-pattern scan (the anti-cheating guard)
    └── log/                        # per-task transcripts + gate output
```

## Design detail

### 1. `CLAUDE.md` — the constitution

Loaded into every single agent process. This is what keeps task #187 consistent with task #3.
Keep it under ~150 lines; it is paid for on every invocation.

Content, lifted from `plans/00-overview.md` §Conventions and §Locked decisions:

- Stack pins: Java 25, Boot 4.1.1, Spring Cloud 2025.1.3, group `com.revolutclone`.
- **Money is always `long` minor units + ISO code. `double` and `float` are banned outright.**
- Every money-moving POST takes `Idempotency-Key`. Every event carries `eventId`,
  `occurredAt`, `correlationId`, `version`. Every Kafka message keyed by the ordering entity.
- Every cross-service write goes through the outbox — never `KafkaTemplate.send()` inside a
  business transaction.
- Flyway owns all schema; `ddl-auto` never exceeds `validate`; migrations run as a
  non-superuser owner.
- No `localhost` in committed config outside the `local` profile. Logs are JSON with
  `correlationId` as a field.
- No module named after a vendor (`common-messaging`, not `common-kafka`).
- The port map (gateway 8080 … simulator 8089; Postgres 5432, Keycloak 8180, Kafka 9092).
- **Boot 4 rule:** when an online Spring answer disagrees with the reference docs, the docs
  are right and the answer is about Boot 3. Prefer `start.spring.io` coordinates over examples.
- **Never weaken a test to make a build pass.** If a test is wrong, fix the test and say so
  in the commit body. Deleting, `@Disabled`-ing or `assumeTrue`-ing a test is a build failure.

### 2. `agent/state.json` — the task ledger

Generated once by parsing the **Task list** section of each `plans/phase-*.md`, then owned by
the harness. One entry per task-list item — roughly 140 tasks across phases 00–09.

```jsonc
{
  "current": "00.4",
  "tasks": [
    {
      "id": "00.4",
      "phase": "phase-00-foundations.md",
      "title": "common-money: Money, CurrencyMismatchException, FxMath. Tests first.",
      "model": "opus",
      "deps": ["00.2", "00.3"],
      "tests": ["MoneyArithmeticTest", "MoneyMismatchTest", "MoneyAllocateTest",
                "MoneyParsingTest", "FxMathTest"],
      "status": "pending",       // pending | in_progress | done | blocked
      "attempts": 0,
      "branch": "task/00.4-common-money",
      "commit": null,
      "notes": null
    }
  ]
}
```

`tests` is copied from the phase's **Tests** table and is what the gate checks *exist and
pass* — this is what stops an agent from declaring a task done with no test coverage.

### 3. `agent/gate.ps1` — the only authority on "done"

Exit 0 means done. Nothing else in the system may declare a task complete. Sequence, all
must pass:

1. `git diff --stat` is non-empty (the agent actually did something).
2. `pwsh agent/tripwire.ps1` — see below.
3. `mvn -q clean verify` from the repo root — full reactor, all modules.
4. Every test named in the task's `tests` array **exists as a file** and appears in the
   surefire/failsafe XML with `failures=0 errors=0 skipped=0`.
5. Test count is `>=` the count recorded at the last green gate (stored in
   `agent/log/baseline.json`). **A dropping test count is a hard failure.** This one check
   catches most forms of cheating that individually look innocent.
6. Phase-specific verification, when the task is the last in its phase — run the phase's
   **Verification** block: `tasks.ps1 up`, container health, `tasks.ps1 topics --describe`
   matching the topic catalogue, the Keycloak token request, `grep -ri localhost`.
7. From Phase 02 onward, the **ledger invariant** against the running database:
   ```sql
   SELECT transaction_id FROM ledger_entry
   GROUP BY transaction_id HAVING SUM(amount_minor) <> 0;
   ```
   Any row returned is a hard failure regardless of what the test suite says. This is the
   one invariant the entire project rests on, and it is cheap to check continuously.

### 4. `agent/tripwire.ps1` — forbidden patterns

A grep pass over the diff. Any hit fails the gate with the offending file:line. Starting set:

| Pattern | Why |
|---|---|
| `@Disabled`, `@Ignore`, `assumeTrue`, `assumeThat` | Test suppression |
| deleted or renamed `*Test.java` not replaced | Test deletion |
| `double`/`float`/`BigDecimal` in a money field, `Money`-adjacent | Violates the money rule (BigDecimal is allowed only in `FxMath` and `toMajor`) |
| `ddl-auto:\s*(update|create)` | Flyway owns schema |
| `kafkaTemplate.send` outside `common-messaging/spring/` | Bypasses the outbox |
| `localhost` in a non-`local` profile yaml | Config hygiene |
| `--force`, `push -f`, `reset --hard`, `docker system prune`, `rm -rf` in any script | Destructive |
| `catch (Exception e) {}` with empty body | Swallowed failure |

Cheap to extend. Every time a run goes wrong in a new way, a line gets added here — the
tripwire file is the harness's accumulated scar tissue.

### 5. `.claude/settings.json` — permissions and hooks

**Permissions.** Unattended runs cannot prompt, but `--dangerously-skip-permissions` is the
wrong tool: it also removes the guard on `git push --force` and `docker volume rm`. Use a
broad *allowlist* plus an explicit *denylist*:

- allow: `Bash(mvn:*)`, `Bash(git:*)` (minus denied), `Bash(docker compose:*)`,
  `Bash(docker ps:*)`, `Bash(npm:*)`, `Bash(psql:*)`, `Bash(curl:*)`,
  `Bash(pwsh:*)`, `Edit`, `Write`, `WebSearch`, `WebFetch(domain:docs.spring.io)`
- deny: `Bash(git push --force*)`, `Bash(git reset --hard*)`,
  `Bash(docker system prune*)`, `Bash(docker volume rm*)`, `Write(./.claude/**)`,
  `Write(./agent/gate.ps1)`, `Write(./agent/tripwire.ps1)`, `Write(./plans/**)`

That last group matters: **the agent must not be able to edit its own gate, tripwire, or the
plans it is being measured against.** Without it, "fully autonomous" degenerates within a
week — a blocked agent will eventually decide the gate is the bug.

**Hooks.**

- `PreToolUse` on `Bash` — a script that hard-denies anything the denylist regex misses.
- `PostToolUse` on `Edit|Write` matching `*.java` — compile just that module
  (`mvn -q -o -pl <module> compile`). Fails fast, inside the same turn, instead of at gate time.
- `Stop` — run `gate.ps1`. On non-zero, return
  `{"decision":"block","reason":"<gate output>"}` so the agent cannot end its turn while the
  build is red. **This hook is the mechanism that makes the whole thing work.** It converts
  "please make sure tests pass" from a request into a property of the runtime.

### 6. Subagents

Three, deliberately few (speed was the priority; each spawn re-derives context):

- **`implementer`** — the default. Full tools. Gets one task-list item plus the phase's
  relevant Design-detail section pasted in.
- **`verifier`** — read-only tool set (no Edit/Write/NotebookEdit). Runs the phase's
  Verification block and reports pass/fail with evidence. Separating it from the implementer
  is what stops "I ran the tests and they passed" being asserted rather than observed.
- **`phase-reviewer`** — runs at each phase boundary against the phase's **Done when**
  checklist plus `docs/architecture.md`. Emits findings; the driver turns findings into new
  tasks in `state.json` rather than merging over them.

### 7. Skills

- **`new-service`** — scaffolds a Spring Boot service to project conventions: module pom,
  `application.yml` with env-var-only endpoints and a `local` profile, Flyway dir including
  the shared `db/migration/common/`, `common-*` dependencies, resource-server config, Docker
  entry, port from the map, health check. Used nine times across phases 01–06. Writing this
  once removes the single largest source of drift between services.
- **`phase-verify`** — executes a phase's Verification section verbatim and reports a table.

### 8. `agent/run.ps1` — the driver

```
loop:
  task = first task in state.json with status=pending and all deps done
  if none: all phases complete → stop
  if task.attempts >= 3: mark blocked, write agent/log/BLOCKED-<id>.md, stop the loop
  git checkout -b task/<id>-<slug> from master
  claude -p "<task prompt>" --model <task.model> --append-system-prompt <phase design detail>
         --output-format stream-json  >> agent/log/<id>.jsonl
  # the Stop hook has already enforced gate.ps1 == 0 before the process could exit
  if gate.ps1 == 0:
      git commit, git checkout master, git merge --ff-only, git tag task/<id>
      status = done; record test-count baseline
  else:
      task.attempts++; git branch is kept for inspection; retry
```

**Three consecutive failures halts the whole loop.** This is the circuit breaker. Full
autonomy without one turns a single misunderstanding into an all-night thrash that burns
tokens and leaves a branch graveyard.

Merging is `--ff-only` onto `master`, never a force push. Every task is a tagged commit, so
any bad decision is one `git reset --hard task/<id>` away from recoverable.

### 9. Parallelism

Sequential by default, because the phases compound. Two exceptions worth taking, as parallel
`implementer` subagents within one task:

- **Phase 00 tasks 4–8** — `common-money`, `contracts`, `common-messaging`, `common-web`,
  `common-security` are genuinely independent modules with independent test suites.
- **Phase 03b** — the React foundation is independent of 03a's saga once the API contract
  is fixed.

Everything else runs one at a time.

## Where full autonomy will actually hurt

Stated plainly, since this mode was chosen with the risk known:

1. **Phase 02, the ledger.** `plans/00-overview.md` says it outright: "a wrong ledger in
   Phase 02 poisons everything after it." Gate check #7 (the continuous balancing invariant)
   is the mitigation, and it is a good one — but it only catches *unbalanced* entries, not a
   *wrong sign convention*. Phase 02's §Accounting sign convention and §Balance column
   semantics are worth ten minutes of human reading when that phase lands, even in
   fully-autonomous mode. It is the single highest-value human checkpoint in the project.
2. **Phase 03a compensation paths.** Sagas fail correctly only when the failure path is
   tested, and failure-path tests are the ones an agent is most likely to write shallowly.
   Require the phase-reviewer to confirm a test exists for *each* compensation branch in
   `docs/architecture.md` §3 before the phase merges.
3. **Phase 00 task 0.** `JAVA_HOME` is currently unset and setting a user environment
   variable is outside what the allowlist should permit. Do this by hand before the first
   run; it costs thirty seconds and otherwise produces failures that look like anything
   except a JDK problem.

## Build order for the harness

1. Set `JAVA_HOME` manually; confirm `java -version` and `mvn -version` both report 25.
2. Confirm the headless flags on the installed version (`claude -p --help`) — pin the exact
   spellings used in `run.ps1` rather than assuming.
3. Write `CLAUDE.md`.
4. Write `.claude/settings.json`: permissions first, then the `PreToolUse` deny hook, then
   the `Stop` gate hook.
5. Write `agent/tripwire.ps1` and `agent/gate.ps1`. **Test these against deliberately bad
   input before anything else** — a bad diff with an `@Disabled`, a diff with a deleted test,
   a red build. A gate that has never been seen to fail is not a gate.
6. Write `agent/state.json` by parsing the ten phase files' Task list sections.
7. Write the three subagents and two skills.
8. Write `agent/run.ps1`.
9. Dry run, see below.

## Verification

**Harness smoke test — do not skip.** Run the driver on **Phase 00 task 4 alone**
(`common-money`). It is the ideal canary: fully self-contained, no infrastructure, and it has
five named tests in the phase's Tests table including a property test over `allocate`.

```powershell
pwsh agent/run.ps1 -Only 00.4
```

Expected: a `task/00.4-common-money` branch, `Money`/`FxMath`/`CurrencyMismatchException`
written, five test classes present, `mvn -q clean verify` green, fast-forward merge to
`master`, tag `task/00.4`, `state.json` showing `done`.

Then verify the gate actually bites, by hand:

```powershell
# 1. tripwire catches suppression
git checkout -b probe; # add @Disabled to MoneyAllocateTest
pwsh agent/gate.ps1            # must exit non-zero naming that file:line

# 2. baseline catches test deletion
rm .../MoneyParsingTest.java
pwsh agent/gate.ps1            # must exit non-zero on dropping test count

# 3. Stop hook actually blocks
#    break a test, run: claude -p "say done and stop"
#    the process must NOT exit until the build is green
```

If any of those three passes when it should fail, stop and fix the gate before running
anything unattended. Then run Phase 00 end to end and check the phase Verification block by
hand once, to confirm the automated version agrees with reality.

## Done when

- [ ] `CLAUDE.md` states the money, idempotency, outbox, Flyway and config rules
- [ ] `.claude/settings.json` allowlist runs a full task with zero prompts, and denies
      `git push --force` and writes to `agent/gate.ps1`, `.claude/**`, `plans/**`
- [ ] `Stop` hook demonstrably prevents exit on a red build
- [ ] `gate.ps1` fails on: no diff, tripwire hit, red build, missing named test, dropping
      test count, unbalanced ledger transaction
- [ ] `state.json` contains every task-list item from phases 00–09 with deps and model
- [ ] Three-strikes circuit breaker halts the loop and writes a blocker note
- [ ] Phase 00 task 4 completes, merges and tags with no human intervention
- [ ] All three gate-bites-back probes fail as designed
