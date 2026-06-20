# Terminal One

A single-user desktop trading cockpit — an NYSE-floor terminal crossed with a late-night
casino slot machine — that monitors your stock/option positions and runs a deterministic,
auditable options-trade recommendation engine.

> Advisory + tracking only. **Not financial advice.** It never routes a live order.

See [`docs/PRD.md`](docs/PRD.md) for the product spec and [`plans/terminal-one.md`](plans/terminal-one.md)
for the phased implementation plan.

## Monorepo layout

```
terminal-one/
├── backend/     Spring Boot 3 (Java 21) — REST API, JWT auth, Postgres, engine (later phases)
├── desktop/     Electron + React + TypeScript — neon thin client
├── docs/        PRD, strategy matrix, runbooks
├── plans/       Phased build plan
└── .github/     CI (test gate)
```

## Status — Phase 1: Walking skeleton ✅

The thinnest end-to-end slice: Electron neon shell → single-account login → JWT (stored in
the OS keychain) → authenticated call to Spring Boot → Postgres-backed identity. No domain
logic yet.

- `GET /api/health` — public liveness
- `POST /api/auth/login` — single account → JWT
- `GET /api/whoami` — JWT-protected `{username, serverTime}` payload
- Every other route requires a valid bearer token (401 otherwise)

## Prerequisites

| Tool | Version used |
|------|--------------|
| JDK | 21 (Corretto) |
| Maven | 3.9+ |
| Node | 22 |
| Docker | for local Postgres |

## Local dev startup

### 1. Postgres (Docker)

```bash
docker run -d --name t1-pg \
  -e POSTGRES_USER=terminalone \
  -e POSTGRES_PASSWORD=terminalone \
  -e POSTGRES_DB=terminalone \
  -p 5432:5432 postgres:16
```

### 2. Backend (Spring Boot)

```bash
cd backend
cp .env.example .env        # edit secrets; .env is gitignored

# Export the .env vars into your shell, then run:
set -a && source .env && set +a
mvn spring-boot:run
```

Flyway creates the schema and the single account is seeded from `APP_USER_USERNAME` /
`APP_USER_PASSWORD` on first start. Verify:

```bash
curl localhost:8080/api/health
```

Run the test gate (no DB required — uses in-memory H2):

```bash
cd backend && mvn test
```

### 3. Desktop (Electron)

```bash
cd desktop
cp .env.example .env        # BACKEND_URL=http://localhost:8080
npm install
npm run dev                 # launches the Electron neon shell
```

Log in with the seeded credentials. The JWT is stored in your OS keychain (macOS Keychain
via `keytar`); the renderer never touches the token or the network directly.

Other desktop scripts: `npm run lint`, `npm run typecheck`, `npm run build`,
`npm run package` (unsigned `.app`, see D13).

## Deploy

Backend deploys to Railway (managed Postgres + Docker). See
[`docs/deploy-railway.md`](docs/deploy-railway.md). CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml))
runs the backend tests + desktop build/lint/typecheck as a merge gate; Railway auto-deploys
the backend on merge to `main`.

## Secrets

All secrets (`JWT_SECRET`, DB creds, `APP_USER_*`, later the market-data key) live only in
Railway env in production and in gitignored `.env` files locally. Nothing secret is committed.
