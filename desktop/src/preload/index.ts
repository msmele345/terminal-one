import { contextBridge, ipcRenderer } from 'electron'

// Typed, minimal surface exposed to the renderer. The renderer never touches
// the network, the token, or the keychain directly.
export interface WhoamiPayload {
  username: string
  authenticated: boolean
  serverTime: string
}

export type LoginResult =
  | { ok: true; username: string; expiresAt: string }
  | { ok: false; error: string }

export type WhoamiResult =
  | { ok: true; payload: WhoamiPayload }
  | { ok: false; error: string }

const api = {
  login: (username: string, password: string): Promise<LoginResult> =>
    ipcRenderer.invoke('auth:login', username, password),
  whoami: (): Promise<WhoamiResult> => ipcRenderer.invoke('auth:whoami'),
  logout: (): Promise<{ ok: true }> => ipcRenderer.invoke('auth:logout'),
  session: (): Promise<{ loggedIn: boolean }> => ipcRenderer.invoke('auth:session')
}

export type TerminalOneApi = typeof api

contextBridge.exposeInMainWorld('api', api)
