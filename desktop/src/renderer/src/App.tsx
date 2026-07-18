import { useCallback, useEffect, useState } from 'react'
import type { WhoamiPayload } from '../../preload'
import { PortfolioConsole } from './portfolio/PortfolioConsole'
import { SlotMachine } from './engine/SlotMachine'
import { LedgerScreen } from './ledger/LedgerScreen'

type View = 'loading' | 'login' | 'authed'

// Phase 7 AC5 (D18): Portfolio Console, Slot Machine, and Ledger are separate
// screens — only one renders at a time, so the casino metaphor stays confined
// to the recommendation moment and the terminal panels stay conventional.
type Screen = 'console' | 'slotMachine' | 'ledger'

export default function App(): JSX.Element {
  const [view, setView] = useState<View>('loading')
  const [screen, setScreen] = useState<Screen>('console')
  const [appVersion, setAppVersion] = useState<string | null>(null)
  const [, setPayload] = useState<WhoamiPayload | null>(null)
  const [error, setError] = useState<string | null>(null)

  // On boot, if a token sits in the keychain, try to use it.
  const refresh = useCallback(async () => {
    const { loggedIn, appVersion: currentVersion } = await window.api.session()
    setAppVersion(currentVersion)
    if (!loggedIn) {
      setView('login')
      return
    }
    const res = await window.api.whoami()
    if (res.ok) {
      setPayload(res.payload)
      setView('authed')
    } else {
      if (res.error === 'Session expired') {
        setError('Session expired. Sign in again.')
      }
      setView('login')
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  // Phase 9 AC1: a click on the post-EOD-batch desktop notification deep-links
  // into the Slot Machine (the screen only renders once authenticated).
  useEffect(() => {
    return window.api.navigation.onOpenSlotMachine(() => setScreen('slotMachine'))
  }, [])

  // Phase 9 AC3: any authenticated request that discovers an expired JWT
  // returns the entire app to the login seam instead of stranding a screen.
  useEffect(() => {
    return window.api.onSessionExpired(() => {
      setPayload(null)
      setScreen('console')
      setError('Session expired. Sign in again.')
      setView('login')
    })
  }, [])

  return (
    <div className="app">
      <Header
        authed={view === 'authed'}
        appVersion={appVersion}
        screen={screen}
        onNavigate={setScreen}
        onLogout={() => {
          setScreen('console')
          setError(null)
          void refresh()
        }}
      />
      <main className={view === 'authed' ? 'stage stage-wide' : 'stage'}>
        {view === 'loading' && <p className="muted">Booting terminal…</p>}
        {view === 'login' && (
          <LoginCard
            error={error}
            onError={setError}
            onSuccess={() => {
              setError(null)
              void refresh()
            }}
          />
        )}
        {view === 'authed' && renderScreen(screen)}
      </main>
      <footer className="disclaimer">
        Personal tool — not financial advice. Advisory &amp; tracking only.
      </footer>
    </div>
  )
}

const SCREENS: Array<{ id: Screen; label: string }> = [
  { id: 'console', label: 'Console' },
  { id: 'slotMachine', label: 'Slot Machine' },
  { id: 'ledger', label: 'Ledger' }
]

function renderScreen(screen: Screen): JSX.Element {
  switch (screen) {
    case 'slotMachine':
      return <SlotMachine />
    case 'ledger':
      return <LedgerScreen />
    case 'console':
    default:
      return <PortfolioConsole />
  }
}

function Header({
  authed,
  appVersion,
  screen,
  onNavigate,
  onLogout
}: {
  authed: boolean
  appVersion: string | null
  screen: Screen
  onNavigate: (screen: Screen) => void
  onLogout: () => void
}): JSX.Element {
  const handleLogout = async (): Promise<void> => {
    await window.api.logout()
    onLogout()
  }
  return (
    <header className="topbar">
      <div className="brand">
        <span className="brand-mark">◆</span>
        <span className="brand-name">TERMINAL&nbsp;ONE</span>
        <span className="brand-tag">
          personal trading cockpit{appVersion ? ` · v${appVersion}` : ''}
        </span>
      </div>
      {authed && (
        <nav className="screen-nav" aria-label="Screens">
          {SCREENS.map((s) => (
            <button
              key={s.id}
              type="button"
              className="screen-tab"
              aria-current={screen === s.id ? 'page' : undefined}
              onClick={() => onNavigate(s.id)}
            >
              {s.label}
            </button>
          ))}
        </nav>
      )}
      {authed && (
        <button className="ghost-btn" onClick={handleLogout}>
          Log out
        </button>
      )}
    </header>
  )
}

function LoginCard({
  error,
  onError,
  onSuccess
}: {
  error: string | null
  onError: (e: string | null) => void
  onSuccess: () => void
}): JSX.Element {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (e: React.FormEvent): Promise<void> => {
    e.preventDefault()
    setBusy(true)
    onError(null)
    const res = await window.api.login(username, password)
    setBusy(false)
    if (res.ok) {
      onSuccess()
    } else {
      onError(res.error)
    }
  }

  return (
    <form className="card" onSubmit={submit}>
      <h1 className="card-title">SIGN&nbsp;IN</h1>
      <label className="field">
        <span>Username</span>
        <input
          autoFocus
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          autoComplete="username"
        />
      </label>
      <label className="field">
        <span>Password</span>
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
        />
      </label>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <button className="primary-btn" type="submit" disabled={busy || !username || !password}>
        {busy ? 'Authenticating…' : 'Sign in'}
      </button>
    </form>
  )
}
