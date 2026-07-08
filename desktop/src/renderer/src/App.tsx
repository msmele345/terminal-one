import { useCallback, useEffect, useState } from 'react'
import type { WhoamiPayload } from '../../preload'
import { PortfolioConsole } from './portfolio/PortfolioConsole'
import { SlotMachine } from './engine/SlotMachine'

type View = 'loading' | 'login' | 'authed'

export default function App(): JSX.Element {
  const [view, setView] = useState<View>('loading')
  const [, setPayload] = useState<WhoamiPayload | null>(null)
  const [error, setError] = useState<string | null>(null)

  // On boot, if a token sits in the keychain, try to use it.
  const refresh = useCallback(async () => {
    const { loggedIn } = await window.api.session()
    if (!loggedIn) {
      setView('login')
      return
    }
    const res = await window.api.whoami()
    if (res.ok) {
      setPayload(res.payload)
      setView('authed')
    } else {
      setView('login')
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  return (
    <div className="app">
      <Header authed={view === 'authed'} onLogout={refresh} />
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
        {view === 'authed' && (
          <>
            <PortfolioConsole />
            <SlotMachine />
          </>
        )}
      </main>
      <footer className="disclaimer">
        Personal tool — not financial advice. Advisory &amp; tracking only.
      </footer>
    </div>
  )
}

function Header({ authed, onLogout }: { authed: boolean; onLogout: () => void }): JSX.Element {
  const handleLogout = async (): Promise<void> => {
    await window.api.logout()
    onLogout()
  }
  return (
    <header className="topbar">
      <div className="brand">
        <span className="brand-mark">◆</span>
        <span className="brand-name">TERMINAL&nbsp;ONE</span>
        <span className="brand-tag">walking skeleton · v0.1</span>
      </div>
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
      {error && <p className="error">{error}</p>}
      <button className="primary-btn" type="submit" disabled={busy || !username || !password}>
        {busy ? 'Authenticating…' : 'Pull the lever'}
      </button>
    </form>
  )
}
