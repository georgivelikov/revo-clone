# web

React 19 + TypeScript + Vite frontend. Mobile-first (max-width 430px column), dark theme,
bottom tab bar - see `plans/phase-03-payments-and-ui.md` for the design spec.

## Local dev

```bash
cp .env.example .env.local   # Keycloak authority must stay on 127.0.0.1 (its pinned issuer); see the comments in the file
npm install
npm run dev                  # http://localhost:5173
```

Requires Keycloak (`docker compose -f ../docker-compose.infra.yml up -d keycloak`) with the
`revolut` realm imported from `infra/keycloak/revolut-realm.json`, and the gateway
(`api-gateway`, port 8080) for API calls added in later tasks.

## Scripts

- `npm run dev` - Vite dev server
- `npm run build` - `tsc -b` then `vite build`
- `npm run lint` - oxlint
- `npm run preview` - serve the production build locally

## End-to-end tests (Playwright)

Browser tests against the **running compose stack** (`..\start.ps1` first, seeded). They use the
system Chrome, so no browser download is needed.

```bash
npm run e2e          # runs once per origin: localhost:5173 and 127.0.0.1:5173
```

- `e2e/smoke.spec.ts` - real Keycloak login, Home loads with balances and no failed API call. Run
  on both origins because each is a different browser origin for CORS and the WebSocket.
- `e2e/journeys.spec.ts` - vaults, analytics, activity deep link, exchange, card, an Alice -> Bob
  P2P transfer settling in Activity, and logout.

The transfer test moves EUR 1.00 per run (per origin) between demo users. Override the stack with
`E2E_KEYCLOAK`, `E2E_GATEWAY`, `E2E_PASSWORD`.
