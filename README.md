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
├── backend/     Spring Boot 3 (Java 21) — REST API, JWT auth, Postgres, deterministic engine
├── desktop/     Electron + React + TypeScript — neon thin client
├── docs/        PRD, strategy matrix, runbooks
├── plans/       Phased build plan
└── .github/     CI (test gate)
```

## Status — V1 / Phase 9

The V1 feature path is complete: authenticated Portfolio Console, delayed market data and
charts, deterministic recommendation engine, Slot Machine results, paper/taken Ledger,
directional backtest, scheduled EOD processing, and one native EOD notification. Phase 9
adds consistent screen states, session-expiry recovery, an app-level disclaimer, and an
unsigned macOS artifact.

Every route except health and login requires a valid bearer token. For the exact current
phase record and test evidence, use [`plans/terminal-one.md`](plans/terminal-one.md); for the
engine rules and defaults, use [`docs/strategy-matrix.md`](docs/strategy-matrix.md).

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
docker compose up -d            # uses docker-compose.yml (waits until healthy)
```

<details><summary>or a one-off <code>docker run</code></summary>

```bash
docker run -d --name t1-pg \
  -e POSTGRES_USER=terminalone \
  -e POSTGRES_PASSWORD=terminalone \
  -e POSTGRES_DB=terminalone \
  -p 5432:5432 postgres:16
```
</details>

To re-verify the Flyway migrations from scratch, wipe the volume first:
`docker compose down -v && docker compose up -d`.

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

Run the backend test gate from the repository root:

```bash
mvn -f backend/pom.xml test
```

Most of the suite runs on in-memory H2 and needs no database. One test —
`SchemaMigrationIntegrationTest`, the schema gate — starts a real Postgres 16 via
Testcontainers, applies every Flyway migration, and boots the app with
`ddl-auto=validate`, exactly as Railway does. It is what catches a migration that
does not apply or an entity that has drifted from the migrated schema; the H2 tests
generate their schema from the entities and are structurally blind to both. It needs
the Docker daemon running (already a prerequisite above) and is deliberately not
skipped when Docker is absent — a gate that can silently skip is not a gate.

#### Run fully offline (stub market data)

No MarketData.app token? Boot under the `stub` profile and the backend serves deterministic,
in-house-priced quotes/chains/daily-bars for **any** symbol — the console prices positions,
renders charts + totals, and the ATM-IV job records readings, using Postgres but **no vendor
credentials**:

```bash
cd backend
set -a && source .env && set +a                 # DB creds only; MARKETDATA_TOKEN not needed
SPRING_PROFILES_ACTIVE=stub mvn spring-boot:run
```

Then start the desktop (below) and log in — the console comes alive against synthetic data.
Stub prices are stable per symbol but **not real market levels** (P&L is illustrative). The
real MarketData.app provider is the default whenever the profile is off. Full walkthrough:
[`VERIFY-PHASE3.md`](VERIFY-PHASE3.md).

### 3. Desktop (Electron)

```bash
cd desktop
cp .env.example .env        # BACKEND_URL=http://localhost:8080
npm install
npm run dev                 # launches the Electron neon shell
```

Log in with the seeded credentials. The JWT is stored in your OS keychain (macOS Keychain
via `keytar`); the renderer never touches the token or the network directly.

Other desktop scripts: `npm test` (renderer + pure main-process tests, Vitest), `npm run lint`,
`npm run typecheck`, and `npm run build`.

## Build and install the macOS app

GitHub Actions packages the tested app on an Apple Silicon runner and uploads an unsigned DMG
named `terminal-one-macos-arm64-<commit-sha>`. The packaged client embeds the production
Railway backend URL; credentials and market-data secrets remain server-side.

For artifact download, local packaging, the one-time Gatekeeper exception, and macOS
notification permission, follow [`docs/macos-first-run.md`](docs/macos-first-run.md).

## Deploy

Backend deploys to Railway (managed Postgres + Docker). See
[`docs/deploy-railway.md`](docs/deploy-railway.md). CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml))
runs backend and desktop gates, then packages the macOS artifact; Railway auto-deploys the
backend on merge to `main`.

Engine tuning is a versioned database operation, not a code deploy. Follow
[`docs/config-update-runbook.md`](docs/config-update-runbook.md) to inspect, activate, verify,
or roll back a config version.

## Secrets

All secrets (`JWT_SECRET`, DB creds, `APP_USER_*`, and the market-data key) live only in
Railway env in production and in gitignored `.env` files locally. Nothing secret is committed.

## Developer-only artifacts

- [`spikes/`](spikes/) contains the MarketData.app probe and fixture-capture utilities used to
  validate the provider contract. They are not packaged with the desktop app or backend image.
- [`VERIFY-PHASE3.md`](VERIFY-PHASE3.md) is the retained stub-provider verification walkthrough.

## Post-V1 maintenance notes

- `keytar` is archived upstream. Migrate the JWT-at-rest seam to Electron `safeStorage` in
  V1.5/V2; V1 continues to use the OS keychain through `keytar`.
- The renderer bundle is intentionally acceptable for a local Electron app. Revisit splitting
  only if measured startup time becomes a problem.
