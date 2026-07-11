# Railway CLI — Quick Reference

Installation, auth, and common commands for CI/CD workflows on the `terminal-one` project.

## Install

```bash
# macOS (Homebrew)
brew install railway

# npm (any platform)
npm install -g @railway/cli

# Shell script (macOS/Linux)
curl -fsSL https://railway.app/install.sh | sh
```

## Auth & Project Setup

| Command | What it does |
|---------|-------------|
| `railway login` | Browser-based auth (one-time) |
| `railway whoami` | Show which account you're logged into |
| `railway link` | Link current directory to an existing Railway project (picks up `railway.toml`) |
| `railway init` | Create a new Railway project from your current directory |
| `railway status` | Show what project/environment is linked |

## Environment Management

| Command | What it does |
|---------|-------------|
| `railway environment` | List all environments (production, develop, etc.) |
| `railway environment production` | Switch to the production environment |
| `railway environment create staging` | Create a staging environment |

## Variables (Secrets & Config)

| Command | What it does |
|---------|-------------|
| `railway variables list` | List all variables in the current environment |
| `railway variables set KEY=VALUE` | Set a variable (e.g., `railway variables set JWT_SECRET=abc123...`) |
| `railway variables delete KEY` | Remove a variable |
| `railway variables get KEY` | Read one variable's value |
| `railway variables --service backend set FOO=bar` | Set a variable scoped to a specific service |

> This is how you rotate `JWT_SECRET` or `APP_USER_PASSWORD` without triggering a deploy.

## Local Development & Debugging

| Command | What it does |
|---------|-------------|
| `railway run <command>` | Run any command with Railway env vars injected locally (e.g., `railway run ./mvnw spring-boot:run`) |
| `railway run --service backend <cmd>` | Run against a specific service's env |
| `railway shell` | Open a shell with all Railway env vars loaded |
| `railway logs` | Stream live logs from your deployed service |

> `railway run` pulls down all remote env vars and injects them locally. Perfect for debugging a production bug without hardcoding secrets.

## Deploy Commands

| Command | What it does |
|---------|-------------|
| `railway up` | Deploy from the current directory (builds + deploys in one shot) |
| `railway up --detach` | Deploy and don't wait for the result (CI-friendly) |
| `railway up --service backend` | Deploy a specific service |
| `railway up --environment production` | Deploy to a specific environment |

## Service Management

| Command | What it does |
|---------|-------------|
| `railway service` | List services in the project |
| `railway service logs` | Show recent logs for a service |
| `railway service shell` | SSH into a running service container (interactive debugging) |

## CI/CD Workflow

Typical flow for GitHub Actions + Railway:

```bash
# 1. Install the CLI in CI (already done by most Railway GitHub Actions)
railway login --browserless   # or use RAILWAY_TOKEN env var

# 2. Link to your project
railway link --project-id <your-project-id>

# 3. Optionally: set an immediate variable (no deploy needed)
railway variables set RELEASE_TAG=v1.2.3

# 4. Deploy from CI
railway up --detach --service backend

# 5. Check deployment status
railway status
```

## What `railway.toml` Controls

The CLI reads this file automatically on `railway up`. From the `terminal-one` backend config:

```toml
[build]
builder = "DOCKERFILE"            # Uses multi-stage Docker build
dockerfilePath = "Dockerfile"

[deploy]
startCommand = "java -jar /app/app.jar"
healthcheckPath = "/api/health"
healthcheckTimeout = 120           # Waits up to 120s for /api/health → 200
restartPolicyType = "ON_FAILURE"
restartPolicyMaxRetries = 5
```

You don't need to specify these on the CLI — `railway up` reads them from `railway.toml` automatically.

## Pro Tips for This Project

1. **Local prod debugging**: `railway run --service backend ./mvnw spring-boot:run` launches the backend locally with all production env vars (including Postgres connection from Railway's plugin).

2. **Wait for CI gates**: Railway's "Wait for CI" toggle in service settings causes `railway up` to block until GitHub Actions checks pass — no branch-protection rules needed beyond that.

3. **Shell into production**: `railway service shell` drops you into a running container for live debugging (the Dockerfile uses a non-root user).

4. **Logs streaming**: `railway logs -f` tails production logs. Useful after a deploy to watch the health check pass and Flyway migrations run.

## Related Docs

- [Deploy — Railway](./deploy-railway.md) — one-time setup, env var mapping, and continuous deploy flow
- [`railway.toml`](../backend/railway.toml) — build/deploy config for the backend service
- [`backend/Dockerfile`](../backend/Dockerfile) — multi-stage Maven → JRE Docker build
