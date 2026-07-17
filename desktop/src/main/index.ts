import { app, BrowserWindow, ipcMain, Notification, shell } from 'electron'
import { join } from 'path'
import keytar from 'keytar'
import { EodBatchWatcher, type EodBatchSummary } from './eodNotifier'

// All HTTP to the backend happens here in the main process (Node, no CORS),
// keeping the API base URL and the JWT out of the renderer entirely.
const BACKEND_URL = process.env.BACKEND_URL ?? 'http://localhost:8080'
const KEYCHAIN_SERVICE = 'terminal-one'
const KEYCHAIN_ACCOUNT = 'session-jwt'

const isDev = !app.isPackaged

function createWindow(): void {
  const win = new BrowserWindow({
    width: 1100,
    height: 760,
    minWidth: 880,
    minHeight: 600,
    backgroundColor: '#0a0a12', // neon-terminal base; avoids white flash on load
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: join(__dirname, '../preload/index.mjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  })

  win.once('ready-to-show', () => win.show())

  // Open external links in the OS browser, never in-app.
  win.webContents.setWindowOpenHandler(({ url }) => {
    void shell.openExternal(url)
    return { action: 'deny' }
  })

  if (isDev && process.env.ELECTRON_RENDERER_URL) {
    void win.loadURL(process.env.ELECTRON_RENDERER_URL)
  } else {
    void win.loadFile(join(__dirname, '../renderer/index.html'))
  }
}

// ---- Backend calls + keychain-backed session ----

async function login(username: string, password: string) {
  const res = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  })
  if (!res.ok) {
    return { ok: false as const, error: res.status === 401 ? 'Invalid credentials' : `Login failed (${res.status})` }
  }
  const body = (await res.json()) as { token: string; username: string; expiresAt: string }
  await keytar.setPassword(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT, body.token)
  return { ok: true as const, username: body.username, expiresAt: body.expiresAt }
}

async function whoami() {
  const token = await keytar.getPassword(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT)
  if (!token) return { ok: false as const, error: 'Not authenticated' }
  const res = await fetch(`${BACKEND_URL}/api/whoami`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  if (res.status === 401) {
    // Stale/expired token — clear it so the UI returns to login.
    await keytar.deletePassword(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT)
    return { ok: false as const, error: 'Session expired' }
  }
  if (!res.ok) return { ok: false as const, error: `Request failed (${res.status})` }
  return { ok: true as const, payload: await res.json() }
}

async function logout() {
  await keytar.deletePassword(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT)
  return { ok: true as const }
}

async function hasSession() {
  const token = await keytar.getPassword(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT)
  return { loggedIn: token != null }
}

// ---- Authenticated backend calls (portfolio) ----

// Wraps a backend fetch with the keychain JWT and uniform error handling, so the
// renderer only ever sees a typed { ok } result and never the token or raw HTTP.
async function authedFetch(path: string, init: RequestInit = {}) {
  const token = await keytar.getPassword(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT)
  if (!token) return { ok: false as const, error: 'Not authenticated' }
  const res = await fetch(`${BACKEND_URL}${path}`, {
    ...init,
    headers: { ...(init.headers ?? {}), Authorization: `Bearer ${token}` }
  })
  if (res.status === 401) {
    await keytar.deletePassword(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT)
    return { ok: false as const, error: 'Session expired' }
  }
  if (res.status === 204) return { ok: true as const, data: null }
  const body = await res.json().catch(() => null)
  if (!res.ok) {
    const message = (body && (body.error as string)) || `Request failed (${res.status})`
    return { ok: false as const, error: message }
  }
  return { ok: true as const, data: body }
}

const JSON_HEADERS = { 'Content-Type': 'application/json' }

async function listPositions() {
  return authedFetch('/api/portfolio/positions')
}

async function portfolioSummary() {
  return authedFetch('/api/portfolio/summary')
}

async function createPosition(request: unknown) {
  return authedFetch('/api/portfolio/positions', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(request)
  })
}

async function updatePosition(id: number, request: unknown) {
  return authedFetch(`/api/portfolio/positions/${id}`, {
    method: 'PUT',
    headers: JSON_HEADERS,
    body: JSON.stringify(request)
  })
}

async function deletePosition(id: number, kind: string) {
  return authedFetch(`/api/portfolio/positions/${id}?kind=${encodeURIComponent(kind)}`, {
    method: 'DELETE'
  })
}

async function importPositions(csv: string) {
  return authedFetch('/api/portfolio/import', {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain' },
    body: csv
  })
}

async function marketDataHistory(symbol: string) {
  return authedFetch(`/api/marketdata/history/${encodeURIComponent(symbol)}`)
}

async function runEngine(symbol?: string) {
  return authedFetch('/api/engine/run', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(symbol ? { symbol } : {})
  })
}

async function ledgerList(configVersion?: number) {
  const query = configVersion != null ? `?configVersion=${configVersion}` : ''
  return authedFetch(`/api/ledger${query}`)
}

async function takeRecommendation(id: number, fillPrice: number) {
  return authedFetch(`/api/recommendations/${id}/take`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ fillPrice })
  })
}

async function takenPositions() {
  return authedFetch('/api/recommendations/taken')
}

// ---- EOD desktop notification (Phase 9 AC1, FR-24/D20) ----

// The backend runs the scheduled EOD batch in the cloud; the main process polls
// its summary and fires exactly one native notification per newly completed
// batch with recommendations. Clicking it deep-links into the Slot Machine.
const EOD_POLL_INTERVAL_MS = 5 * 60_000

async function fetchLatestEodBatch(): Promise<EodBatchSummary | null> {
  const res = await authedFetch('/api/engine/eod/latest')
  // Logged out / backend unreachable / no batch yet (204) all read as
  // "nothing to report"; the watcher just tries again on the next poll.
  if (!res.ok) return null
  return res.data as EodBatchSummary | null
}

function openSlotMachine(): void {
  let win = BrowserWindow.getAllWindows()[0]
  if (!win) {
    createWindow()
    win = BrowserWindow.getAllWindows()[0]
  }
  if (!win) return
  if (win.isMinimized()) win.restore()
  win.show()
  win.focus()
  const send = (): void => win.webContents.send('nav:open-slot-machine')
  if (win.webContents.isLoading()) {
    win.webContents.once('did-finish-load', send)
  } else {
    send()
  }
}

function startEodNotificationWatcher(): void {
  const watcher = new EodBatchWatcher(fetchLatestEodBatch, ({ title, body }) => {
    if (!Notification.isSupported()) return
    const notification = new Notification({ title, body })
    notification.on('click', openSlotMachine)
    notification.show()
  })
  void watcher.check() // baseline immediately so old batches never notify
  setInterval(() => void watcher.check(), EOD_POLL_INTERVAL_MS)
}

function registerIpc(): void {
  ipcMain.handle('auth:login', (_e, username: string, password: string) => login(username, password))
  ipcMain.handle('auth:whoami', () => whoami())
  ipcMain.handle('auth:logout', () => logout())
  ipcMain.handle('auth:session', () => hasSession())
  ipcMain.handle('positions:list', () => listPositions())
  ipcMain.handle('positions:summary', () => portfolioSummary())
  ipcMain.handle('positions:create', (_e, request: unknown) => createPosition(request))
  ipcMain.handle('positions:update', (_e, id: number, request: unknown) => updatePosition(id, request))
  ipcMain.handle('positions:delete', (_e, id: number, kind: string) => deletePosition(id, kind))
  ipcMain.handle('positions:import', (_e, csv: string) => importPositions(csv))
  ipcMain.handle('marketdata:history', (_e, symbol: string) => marketDataHistory(symbol))
  ipcMain.handle('engine:run', (_e, symbol?: string) => runEngine(symbol))
  ipcMain.handle('ledger:list', (_e, configVersion?: number) => ledgerList(configVersion))
  ipcMain.handle('recommendations:take', (_e, id: number, fillPrice: number) =>
    takeRecommendation(id, fillPrice)
  )
  ipcMain.handle('recommendations:taken', () => takenPositions())
}

app.whenReady().then(() => {
  registerIpc()
  createWindow()
  startEodNotificationWatcher()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
