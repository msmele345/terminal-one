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

// ---- Portfolio summary (Phase 3): delayed market value + unrealized P&L ----

// Computed money fields are null when a symbol can't be priced (priced=false),
// never a misleading 0.
export interface StockSummary extends StockPosition {
  markPrice: number | null
  marketValue: number | null
  unrealizedPnl: number | null
  unrealizedPnlPct: number | null
  priced: boolean
}

export interface OptionSummary extends OptionPosition {
  markPrice: number | null
  marketValue: number | null
  unrealizedPnl: number | null
  unrealizedPnlPct: number | null
  priced: boolean
}

export interface PortfolioTotals {
  costValue: number
  marketValue: number
  unrealizedPnl: number
  unrealizedPnlPct: number | null
}

export interface PortfolioSummary {
  stocks: StockSummary[]
  options: OptionSummary[]
  totals: PortfolioTotals
  delayed: boolean
  asOf: string | null
  unpriced: number
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
    summary: (): Promise<ApiResult<PortfolioSummary>> => ipcRenderer.invoke('positions:summary'),
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
