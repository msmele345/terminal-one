# Deploy — Railway (backend)

Terminal One's backend runs always-on on **Railway** (D10, D11): managed Postgres, Docker
build, git-push deploy. A single `production` environment auto-deploys via Railway's native
GitHub integration. The Electron client is built/distributed separately (D13).

## One-time setup

1. **Create a Railway project** and connect this GitHub repo.
2. **Add a Postgres plugin** to the project. Railway injects `DATABASE_URL` and the `PG*`
   variables into the environment.
3. **Add a backend service** from the repo and set its **Root Directory** to `backend`.
   Railway reads [`backend/railway.toml`](../backend/railway.toml) and builds the
   [`backend/Dockerfile`](../backend/Dockerfile).
4. **Set service env vars** (Settings → Variables). Map the Railway Postgres vars to the
   Spring datasource and set the app secrets:

   | Variable | Value |
   |----------|-------|
   | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
   | `SPRING_DATASOURCE_USERNAME` | `${{Postgres.PGUSER}}` |
   | `SPRING_DATASOURCE_PASSWORD` | `${{Postgres.PGPASSWORD}}` |
   | `JWT_SECRET` | a long random string (≥ 32 bytes) |
   | `JWT_EXPIRATION_MINUTES` | `720` (optional) |
   | `APP_USER_USERNAME` | your login |
   | `APP_USER_PASSWORD` | your password (seeded on first boot) |
   | `MARKETDATA_TOKEN` | MarketData.app token for live delayed data |
   | `ENGINE_EOD_CRON` | `0 30 16 * * MON-FRI` (optional; evaluated in America/New_York) |

   > `${{Postgres.*}}` is Railway's reference syntax — it pulls live values from the Postgres
   > plugin without copying secrets. `PORT` is injected automatically; the app reads it.
   > Watch for stray whitespace when pasting the URL — a trailing space breaks the host/port
   > parse and surfaces as a misleading connection/auth error.

5. **Health check**: `railway.toml` sets `healthcheckPath = /api/health`. Confirm the public
   URL returns `200`:

   ```bash
   curl https://<your-service>.up.railway.app/api/health
   ```

## Continuous deploy (Railway native)

The backend service is connected to GitHub and **auto-deploys on push** to its connected branch
(set this to `main` in the service's Settings → Source). The merge flow is:

```
feature/* → PR → develop → PR → main → Railway redeploys production
```

GitHub Actions ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) runs backend tests and
the desktop lint/typecheck/test/build gate on every PR. After both gates pass, a macOS runner
builds and uploads the unsigned Apple Silicon DMG. Enabling **branch protection** on `main` (and
`develop`) that requires the `backend`, `desktop`, and `macos-artifact` checks keeps red or
unpackageable builds from merging — so in practice only tested code reaches production.

> Note: Railway's native deploy and the Actions test gate run **independently** — Railway does not
> wait for Actions unless you enable its **"Wait for CI"** setting (service Settings). For a
> single-user app, branch protection is usually enough. If you later want Railway to block on the
> checks, flip "Wait for CI" on — no workflow changes needed.

## Notes

- The image is a multi-stage Docker build (Maven → JRE), runs as a non-root user, and is
  12-factor + Dockerized for a future mechanical lift to Azure (D11).
- Migrations are Flyway (`backend/src/main/resources/db/migration`); they run automatically on
  startup. Never edit an applied migration — add a new `V{n}__*.sql`.
- Engine config changes (later phases) are **data, not deploys** — see
  [`config-update-runbook.md`](config-update-runbook.md).
- Desktop install and Gatekeeper steps are in [`macos-first-run.md`](macos-first-run.md).
