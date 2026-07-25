## WHAT — Stack & Structure
- Project Name: terminal-one
- Electron/React Thin Client + Spring Boot Backend + Railway
**One-liner:** A single-user desktop trading cockpit — an NYSE-floor terminal crossed with a late-night casino slot machine — that monitors your stock/option positions and runs a deterministic options-trade recommendation engine you can audit.

- CI/CD: Railway

# Project Overview and Plan:
See @docs/PRD.md to review project goals
See @plans/terminal-one.md

## Agent Orientation
- Source of truth order: `plans/terminal-one.md` for current phase scope, `docs/strategy-matrix.md` for engine math/strategy behavior, `docs/PRD.md` for product intent.
- Keep phase work scoped to the named acceptance criteria. Do not implement later-phase matrix cells or UI behavior unless the AC explicitly requires it.
- If a requested AC is already checked in the plan, verify it against code/tests and strengthen coverage if useful; do not rewrite working code without cause.
- Engine invariants: deterministic only, no LLM; read active engine config once per run; persist recommendations with the producing `config_version`; keep indicator/selection logic pure and unit-testable where practical.
- Testing: pure engine math gets unit tests; API/persistence/config behavior gets Spring integration/controller tests; frontend tests are only required for UI behavior changes.


## Cadences to follow:
1. TDD on any new feature code or bug fixes. Use Test Driven Development whenever possible
2. Red green refactor. Reference the /tdd skill and follow it


# Git Strategy and Instructions
- Create feature branches off of develop for each new feature or task. Name branches using the format `feat/short-description` (e.g., `feature/spotify-integration`).
- Git Strategy is Git Flow with the following branches:
    - `main` - production ready code
    - `develop` - latest development code, merged from feature branches
    - `feat/*` - individual feature branches created from develop, merged back into develop when complete
    - `release/*` - created from develop when preparing for a release, merged into main
- PRs should be used to merge feature branches into develop, and release branches into main. PRs should be reviewed and approved by me before merging.
- **Merge method depends on the target branch** (this matters — getting it wrong breaks branch ancestry):
    - `feat/* → develop`: **Squash and Merge** — keeps develop's history clean, one commit per feature.
    - `release/* → main` (and any `develop → main`): **Create a Merge Commit (`--no-ff`), NEVER squash.** Squash-merging into main collapses the shared commits into a brand-new commit with no ancestry link, so Git's merge base for the *next* release stays stuck at the old point and every changed file surfaces spurious `add/add` conflicts. A real merge commit preserves ancestry and keeps subsequent releases conflict-free.
    - If a `release → main` merge ever shows conflicts on every changed file, the cause is a prior squash-merge into main breaking ancestry. Fix: branch the release off develop, `git merge --no-ff -X ours origin/main` into it (keeps develop's content, brings main's tip in as a parent so main becomes an ancestor), then open the release PR — it will be conflict-free — and merge it with a merge commit.
- Commit messages should follow best practices and use the format: (feat:, chore:, fix:, docs:, refactor:) Examples:
    - `feat: add new widget for genre breakdown`
    - `chore: minor tasks like updating dependencies or fixing typos`
    - `fix: resolve bug in Spotify API integration`
    - `docs: update README with setup instructions`
    - `refactor: service layer redesign`