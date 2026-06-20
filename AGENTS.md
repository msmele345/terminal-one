## WHAT — Stack & Structure
- Project Name: terminal-one
- Electron/React Thin Client + Spring Boot Backend + Railway
**One-liner:** A single-user desktop trading cockpit — an NYSE-floor terminal crossed with a late-night casino slot machine — that monitors your stock/option positions and runs a deterministic options-trade recommendation engine you can audit.

- CI/CD: Railway

# Project Overview and Plan:
See @docs/PRD.md to review project goals
See @plans/terminal-one.md


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
- Use Squash and Merge for all PRs to keep a clean commit history.
- Commit messages should follow best practices and use the format: (feat:, chore:, fix:, docs:, refactor:) Examples:
    - `feat: add new widget for genre breakdown`
    - `chore: minor tasks like updating dependencies or fixing typos`
    - `fix: resolve bug in Spotify API integration`
    - `docs: update README with setup instructions`
    - `refactor: service layer redesign`