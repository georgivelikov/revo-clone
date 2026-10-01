# k6 load scripts

plans/phase-08-load-and-observability.md's scenario table, one file each. `lib/` is the shared
setup module every script imports - it registers and funds `USER_COUNT` fresh test users once in
`setup()`, never from `default()` (which runs per-VU-per-iteration and would register a new user
on every request).

| Script | Shape | Question it answers |
|---|---|---|
| `transfers.js` | Ramp 10 → 500 rps over 5 min, hold 5 min | Steady-state throughput and where p99 breaks |
| `mixed.js` | 60% transfers, 20% FX, 15% card auth, 5% reads | Realistic contention across services |
| `spike.js` | 50 rps → 1000 rps instantly, 60 s, back down | Lag spike and drain time |
| `soak.js` | 100 rps (duration overridable, default 2h) | Leaks, unbounded growth, slow drift |
| `hotspot.js` | 500 rps all targeting one account | Worst case for optimistic locking |

## Running one

k6 isn't installed on the host; every script runs via the official image on the compose network,
so service DNS names (`api-gateway`, `keycloak`, `ledger-service`, `simulator-service`) resolve
the same way they do for every other container here:

```powershell
docker run --rm -i --network revolut_default `
    -v "${PWD}/tools/k6:/scripts" `
    grafana/k6 run /scripts/transfers.js
```

```bash
docker run --rm -i --network revolut_default \
    -v "$(pwd)/tools/k6:/scripts" \
    grafana/k6 run /scripts/transfers.js
```

Needs the full stack up, including the chaos-profile-free base (`docker-compose.infra.yml` +
`docker-compose.yml`, `--profile services`) - the Grafana dashboard (task 08.4) is how you watch
a run, so bring `docker-compose.obs.yml` up too.

Every script reads its endpoints from `lib/config.js`, overridable with `-e`:

| Variable | Default | |
|---|---|---|
| `BASE_URL` | `http://api-gateway:8080` | the gateway |
| `KEYCLOAK_URL` | `http://keycloak:8080/realms/revolut/protocol/openid-connect/token` | |
| `LEDGER_URL` | `http://ledger-service:8083` | funding only - not gateway-routed |
| `SIMULATOR_URL` | `http://simulator-service:8089` | card-auth simulation |
| `USER_COUNT` | varies per script | how many test users `setup()` creates |
| `FUND_AMOUNT_MINOR` | `500000000` (5,000,000.00) | per user, per currency |
| `K6_RUN_ID` | current timestamp | namespaces test user emails across runs |

`mixed.js`, `spike.js` and `soak.js` also take `RATE`/`DURATION`; `hotspot.js` takes
`SENDER_COUNT`/`DURATION`. See each script's own header comment for the exact invocation.

## Known gaps in what these scripts can currently measure

- **`mixed.js`'s FX slice fails outright today** - not a script bug. docs/failure-modes.md §3
  documents a relayed-bearer-token bug across Resilience4j's circuit-breaker thread that makes
  every cross-currency transfer fail with `FX_QUOTE_UNAVAILABLE`, independent of load. The script
  still issues the FX requests and checks them (so the failure shows up in results, not silently)
  - fixing the underlying bug is out of scope for this phase.
- **`hotspot.js` and `spike.js` have no `http_req_failed` threshold.** Both scenarios are designed
  to push the system past where it degrades gracefully - a hot account past its retry budget, an
  instant 20x step - so a real run failing a large fraction of requests is the measurement, not a
  broken script. Read the failure rate from the summary and the dashboard, not from k6's exit
  code.
