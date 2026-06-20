# Deploy — Railway (backend)

Terminal One's backend runs always-on on **Railway** (D10, D11): managed Postgres, Docker
build, git-push deploy. The Electron client is built/distributed separately (D13).

## One-time setup

1. **Create a Railway project** and connect this GitHub repo.
2. **Add a Postgres plugin** to the project. Railway injects `DATABASE_URL` and the `PG*`
   variables into the project.
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

   > `${{Postgres.*}}` is Railway's reference syntax — it pulls live values from the Postgres
   > plugin without copying secrets. `PORT` is injected automatically; the app reads it.

5. **Health check**: `railway.toml` sets `healthcheckPath = /api/health`. Confirm the public
   URL returns `200`:

   ```bash
   curl https://<your-service>.up.railway.app/api/health
   ```

## Continuous deploy

Railway watches the `main` branch and redeploys on every merge. The GitHub Actions test gate
([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) must pass before a PR can merge,
so only green builds reach production.

## Notes

- The image is a multi-stage Docker build (Maven → JRE), runs as a non-root user, and is
  12-factor + Dockerized for a future mechanical lift to Azure (D11).
- Migrations are Flyway (`backend/src/main/resources/db/migration`); they run automatically on
  startup. Never edit an applied migration — add a new `V{n}__*.sql`.
- Engine config changes (later phases) are **data, not deploys** — see
  [`config-update-runbook.md`](config-update-runbook.md).
