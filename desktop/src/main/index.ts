import { app, BrowserWindow, ipcMain, shell } from 'electron'
import { join } from 'path'
import keytar from 'keytar'

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

function registerIpc(): void {
  ipcMain.handle('auth:login', (_e, username: string, password: string) => login(username, password))
  ipcMain.handle('auth:whoami', () => whoami())
  ipcMain.handle('auth:logout', () => logout())
  ipcMain.handle('auth:session', () => hasSession())
}

app.whenReady().then(() => {
  registerIpc()
  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
