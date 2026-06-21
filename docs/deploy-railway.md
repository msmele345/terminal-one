# Deploy — Railway (backend)

Terminal One's backend runs always-on on **Railway** (D10, D11): managed Postgres, Docker
build. Deploys are **Actions-gated** — GitHub Actions runs the test gate and only then runs
`railway up` (see [Actions-gated deploy](#actions-gated-deploy)). The Electron client is
built/distributed separately (D13).

Two Railway **environments** in one project:

| Branch | Railway environment | Notes |
|--------|---------------------|-------|
| `main` | `production` | always-on (runs the EOD batch, later phases) |
| `develop` | `staging` | enable **App Sleeping** to keep costs near-zero |

## One-time setup (production environment)

1. **Create a Railway project** and connect this GitHub repo.
2. **Add a Postgres plugin** to the project. Railway injects `DATABASE_URL` and the `PG*`
   variables into the environment.
3. **Add a backend service** from the repo and set its **Root Directory** to `backend`.
   Railway reads [`backend/railway.toml`](../backend/railway.toml) and builds the
   [`backend/Dockerfile`](../backend/Dockerfile). Note the **service name** — it's passed to
   `railway up --service` (defaults to `terminal-one-backend`; override with the
   `RAILWAY_SERVICE_NAME` GitHub repo variable if yours differs).
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

   > `${{Postgres.*}}` is Railway's reference syntax — it pulls live values from the Postgres
   > plugin without copying secrets. `PORT` is injected automatically; the app reads it.

5. **Health check**: `railway.toml` sets `healthcheckPath = /api/health`. Confirm the public
   URL returns `200`:

   ```bash
   curl https://<your-service>.up.railway.app/api/health
   ```

## Staging environment (develop)

1. In the project, **New Environment** → `staging` (fork `production` to duplicate the service
   + Postgres topology).
2. On the staging backend service, set the **Root Directory** to `backend` (carried over by the
   fork) and give it its **own Postgres** — re-point `SPRING_DATASOURCE_*` at the staging
   Postgres and set fresh `JWT_SECRET` / `APP_USER_*` (variables are per-environment, so prod
   secrets never leak into staging).
3. Enable **App Sleeping** (service Settings → serverless) so staging scales to zero when idle.
   Only `production` needs to stay always-on; staging wakes on request (a few-second cold start).
   This keeps the second environment in the low-single-digit-dollars range.

> Do **not** point the Railway service at a branch for auto-deploy — deploys are driven by
> GitHub Actions (below), so the test gate is a true precondition.

## Actions-gated deploy

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs the backend tests + desktop
build/lint/typecheck on every PR and push. On a **push** to a deploy branch, a `deploy` job
runs **only after both test jobs pass** and calls `railway up`:

- push to `develop` → Railway **staging**
- push to `main` → Railway **production**

### Wire it up (GitHub side)

1. **Create two project tokens in Railway** — one per environment:
   Project → Settings → Tokens → *New Token*, scoped to `production`, then another scoped to
   `staging`. (Project tokens are environment-scoped and free; they don't consume a seat.)
2. **Create two GitHub Environments** (repo → Settings → Environments): `production` and
   `staging`. In each, add a secret named **`RAILWAY_TOKEN`** set to that environment's Railway
   project token. The workflow selects the right Environment by branch, so `secrets.RAILWAY_TOKEN`
   resolves to the matching token automatically.
3. *(Optional)* If your Railway service isn't named `terminal-one-backend`, set a repo
   **variable** `RAILWAY_SERVICE_NAME` to the actual name.
4. *(Recommended)* Enable **branch protection** on `main` (and `develop`) requiring the
   `backend` + `desktop` checks to pass — this makes the test gate block merges, complementing
   the deploy gate. Optionally add a required-reviewer protection rule on the `production`
   GitHub Environment for a manual approval before prod deploys.

> Because the repo is public, GitHub Actions minutes are free and the build runs on Railway, so
> this adds no deploy cost beyond the production/staging resources themselves.

## Notes

- The image is a multi-stage Docker build (Maven → JRE), runs as a non-root user, and is
  12-factor + Dockerized for a future mechanical lift to Azure (D11).
- Migrations are Flyway (`backend/src/main/resources/db/migration`); they run automatically on
  startup. Never edit an applied migration — add a new `V{n}__*.sql`.
- Engine config changes (later phases) are **data, not deploys** — see
  [`config-update-runbook.md`](config-update-runbook.md).
