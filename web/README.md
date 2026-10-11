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
