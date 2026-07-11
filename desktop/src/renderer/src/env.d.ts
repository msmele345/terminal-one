/// <reference types="vite/client" />
import type { TerminalOneApi } from '../../preload'

declare global {
  interface Window {
    api: TerminalOneApi
  }
}

export {}
