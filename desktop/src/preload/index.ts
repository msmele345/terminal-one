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

// ---- Portfolio (Phase 2) ----

export interface StockPosition {
  id: number
  kind: 'STOCK'
  symbol: string
  quantity: number
  costBasis: number
  openedDate: string
}

export interface OptionPosition {
  id: number
  kind: 'OPTION'
  underlying: string
  optionType: 'CALL' | 'PUT'
  strike: number
  expiry: string
  quantity: number
  costBasis: number
  side: 'LONG' | 'SHORT'
  openedDate: string | null
}

export type Position = StockPosition | OptionPosition

export interface PositionsPayload {
  stocks: StockPosition[]
  options: OptionPosition[]
}

export interface ImportResult {
  importedStocks: number
  importedOptions: number
  errors: { line: number; message: string }[]
}

// Request shape sent to the backend; option-only fields omitted for stock.
export interface PositionRequest {
  kind: 'STOCK' | 'OPTION'
  symbol: string
  quantity: number
  costBasis: number
  openedDate?: string | null
  optionType?: 'CALL' | 'PUT'
  strike?: number
  expiry?: string
  side?: 'LONG' | 'SHORT'
}

export type ApiResult<T> = { ok: true; data: T } | { ok: false; error: string }

const api = {
  login: (username: string, password: string): Promise<LoginResult> =>
    ipcRenderer.invoke('auth:login', username, password),
  whoami: (): Promise<WhoamiResult> => ipcRenderer.invoke('auth:whoami'),
  logout: (): Promise<{ ok: true }> => ipcRenderer.invoke('auth:logout'),
  session: (): Promise<{ loggedIn: boolean }> => ipcRenderer.invoke('auth:session'),
  positions: {
    list: (): Promise<ApiResult<PositionsPayload>> => ipcRenderer.invoke('positions:list'),
    create: (request: PositionRequest): Promise<ApiResult<Position>> =>
      ipcRenderer.invoke('positions:create', request),
    update: (id: number, request: PositionRequest): Promise<ApiResult<Position>> =>
      ipcRenderer.invoke('positions:update', id, request),
    remove: (id: number, kind: 'STOCK' | 'OPTION'): Promise<ApiResult<null>> =>
      ipcRenderer.invoke('positions:delete', id, kind),
    import: (csv: string): Promise<ApiResult<ImportResult>> =>
      ipcRenderer.invoke('positions:import', csv)
  }
}

export type TerminalOneApi = typeof api

contextBridge.exposeInMainWorld('api', api)
