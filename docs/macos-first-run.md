# macOS Build, Install, and First Run

Terminal One V1 ships as an unsigned Apple Silicon (`arm64`) DMG. It is intentionally
unsigned because it is a private, single-user app and does not use an Apple Developer
certificate (PRD D13).

## Get the CI artifact

Packaging runs on **merges to `main`** and on **manual runs**, not on every push —
it gates nothing, so building a DMG per commit only occupied a macOS runner. Feature
branches and `develop` pushes still get the full backend + desktop test gate; they
just do not produce a DMG.

1. Open the successful GitHub Actions run for the commit you want. If you need a build
   from a branch that has none, go to **Actions → CI → Run workflow**, pick the branch,
   and run it — the `workflow_dispatch` trigger packages any branch on demand.
2. Under **Artifacts**, download `terminal-one-macos-arm64-<commit-sha>`.
3. Unzip the Actions download, open `Terminal One-1.0.0-arm64.dmg`, and drag
   **Terminal One** into **Applications**.

The CI packaging job embeds `https://terminal-one-production.up.railway.app` as the public
backend URL. The URL is not a secret; JWT and market-data credentials are never embedded.

## First launch through Gatekeeper

Because the app is unsigned, double-clicking it may show an "unidentified developer"
warning. Use Apple's one-time exception flow:

1. In Finder, open **Applications**.
2. Control-click **Terminal One**, choose **Open**, then choose **Open** again.
3. If macOS offers only **Done**, open **System Settings → Privacy & Security**, scroll to
   the Terminal One message, choose **Open Anyway**, authenticate, and confirm **Open**.

After that one approval, Terminal One opens normally from Launchpad, Spotlight, or Finder.
Do not disable Gatekeeper system-wide.

## Notifications

The packaged main process checks Electron's `Notification.isSupported()` before creating the
single EOD notification. On the first notification attempt, allow notifications if macOS
prompts. You can also verify or enable them later under **System Settings → Notifications →
Terminal One → Allow Notifications**. Focus modes can suppress banners even when permission
is enabled.

Smoke-check the packaged build by signing in, pulling the lever once, and confirming the
Console, Slot Machine, and Ledger tabs open. The EOD notification itself appears only after a
new scheduled EOD batch completes; reopening the app deliberately does not replay an old one.

## Build locally

On an Apple Silicon Mac with Node 22:

```bash
cd desktop
npm ci
BACKEND_URL=https://terminal-one-production.up.railway.app \
  CSC_IDENTITY_AUTO_DISCOVERY=false npm run dist -- --arm64 --publish never
```

The DMG is written to `desktop/release/`. `build.mac.identity: null` keeps it explicitly
unsigned. For a local backend, use `npm run dev` with `BACKEND_URL=http://localhost:8080`
instead of installing a production DMG.
