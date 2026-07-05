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

// ---- Price history (Phase 3): daily OHLCV bars for the per-symbol chart ----

export interface PriceBar {
  date: string // YYYY-MM-DD
  open: number
  high: number
  low: number
  close: number
  volume: number
}

export interface PriceHistory {
  symbol: string
  asOf: string | null
  delayed: boolean
  bars: PriceBar[]
}

// ---- Engine (Phase 4): deterministic recommendations ----

export type Direction = 'BULLISH' | 'BEARISH' | 'NEUTRAL'
export type VolatilityRegime = 'LOW' | 'NORMAL' | 'HIGH'
// Phase 6 begins adding income overlays on top of the full §2 directional matrix.
export type StrategyType =
  | 'BULL_CALL_DEBIT_SPREAD'
  | 'BEAR_PUT_DEBIT_SPREAD'
  | 'BULL_PUT_CREDIT_SPREAD'
  | 'BEAR_CALL_CREDIT_SPREAD'
  | 'LONG_CALL'
  | 'LONG_PUT'
  | 'COVERED_CALL'
  | 'CASH_SECURED_PUT'

export interface RecommendationLeg {
  action: string // 'BUY' | 'SELL'
  optionSymbol: string
  callPut: 'CALL' | 'PUT'
  strike: number
  expiry: string
  bid: number
  ask: number
  mid: number
  delta: number
}

// Structured, human-readable "why" behind each recommendation (mirrors the
// backend RecommendationRationale). Every value here drove the selection.
export interface RecommendationRationale {
  signals: {
    trendVote: number
    macdVote: number
    rsiVote: number
    directionScore: number
    conviction: number
    emaFast: number
    emaSlow: number
    macdHistogram: number
    rsi: number
  }
  regime: {
    value: VolatilityRegime
    reason: string
    currentIv: number | null
  }
  selection: {
    convictionBand: string
    dte: number
    longDeltaTarget: number
    shortDeltaTarget: number
    selectedLongDelta: number
    selectedShortDelta: number
  }
  pricing: {
    width: number
    entryDebit: number
    breakeven: number
    probabilityOfProfit: number
    maxProfit: number
    maxLoss: number
    riskReward: number
    rawExpectedValue: number
  }
  // Phase 5 AC5 (§7): contracts the engine sized the structure to.
  sizing: {
    contracts: number
    maxLossPerContract: number
    portfolioValue: number
    perTradeRiskPct: number
    riskAmount: number
  } | null
  // Phase 6 AC1: income-overlay mechanics such as a covered-call upside cap.
  incomeOverlay?: {
    label: string
    note: string
    heldShares: number
    contracts: number
    capStrike: number
    premiumPerShare: number
    cappedUpsidePerContract: number
  } | null
  // Phase 6 AC2: cash-secured-put entry suggestion, flagged for required collateral.
  entrySuggestion?: {
    label: string
    note: string
    contracts: number
    strike: number
    premiumPerShare: number
    requiredCapital: number
  } | null
  // Phase 6 AC4: non-blocking caveats such as unavailable earnings screening.
  warnings?: {
    label: string
    note: string
  }[]
}

export interface Recommendation {
  id: number
  symbol: string
  strategy: StrategyType
  direction: Direction
  regime: VolatilityRegime
  conviction: number
  configVersion: number
  expiry: string
  legs: RecommendationLeg[]
  contracts: number
  entryDebit: number
  probabilityOfProfit: number
  maxProfit: number
  maxLoss: number
  riskReward: number
  score: number
  rationale: RecommendationRationale
}

export interface EngineRunResult {
  recommendations: Recommendation[]
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
  },
  marketData: {
    history: (symbol: string): Promise<ApiResult<PriceHistory>> =>
      ipcRenderer.invoke('marketdata:history', symbol)
  },
  engine: {
    // Lever-pull: run the deterministic engine. Omit symbol to run over the
    // whole portfolio; pass one to scope the run to a single underlying.
    run: (symbol?: string): Promise<ApiResult<EngineRunResult>> =>
      ipcRenderer.invoke('engine:run', symbol)
  }
}

export type TerminalOneApi = typeof api

contextBridge.exposeInMainWorld('api', api)
