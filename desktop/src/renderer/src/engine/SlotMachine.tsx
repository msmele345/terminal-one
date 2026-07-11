import { useState } from 'react'
import { motion, useReducedMotion } from 'framer-motion'
import type { EngineAbstention, Recommendation } from '../../../preload'
import { RecommendationCard, formatLabel } from './RecommendationCard'

type RunState = 'idle' | 'running' | 'done' | 'error'

// Phase 7 AC1: the recommendation moment as a slot machine. Pulling the lever
// runs the deterministic engine (POST /api/engine/run) and the reels spin, then
// settle onto the returned top-N recommendations. No engine logic lives here —
// the result rendering reuses the Phase 4–6 RecommendationCard verbatim.

// Default top-N (OQ-6): the reel bank spins this many reels while the run is in
// flight; the resolved cards are however many the engine actually returns.
const REEL_COUNT = 3

// The engine (esp. under the stub profile) can resolve in a few hundred ms —
// too fast to register as a "pull". We hold the reels spinning for at least this
// long so the tumble is always visible, decoupled from backend latency. Tests
// override with 0 to settle instantly.
const DEFAULT_MIN_SPIN_MS = 1400

// A recommendation in the "High" conviction band (strategy-matrix §3, 80–100) is
// the jackpot: the payout animation fires and the card is badged.
const JACKPOT_CONVICTION = 80

// Glyphs the reels tumble through while spinning — casino-meets-ticker.
const REEL_GLYPHS = ['$', '▲', '▼', '◆', '★', '7', '↑', '↓', '⬢']

export function SlotMachine({
  minSpinMs = DEFAULT_MIN_SPIN_MS
}: {
  minSpinMs?: number
} = {}): JSX.Element {
  const [state, setState] = useState<RunState>('idle')
  const [recs, setRecs] = useState<Recommendation[]>([])
  const [abstentions, setAbstentions] = useState<EngineAbstention[]>([])
  const [error, setError] = useState<string | null>(null)
  const reducedMotion = useReducedMotion() ?? false

  const pull = async (): Promise<void> => {
    if (state === 'running') return
    setState('running')
    setError(null)
    setRecs([])
    setAbstentions([])
    const started = Date.now()
    const res = await window.api.engine.run()
    // Keep the reels tumbling until the guaranteed spin window has elapsed, so a
    // fast engine response doesn't rob the pull of its animation.
    const remaining = minSpinMs - (Date.now() - started)
    if (remaining > 0) await new Promise((r) => setTimeout(r, remaining))
    if (res.ok) {
      setRecs(res.data.recommendations)
      setAbstentions(res.data.abstentions ?? [])
      setState('done')
    } else {
      setError(res.error)
      setState('error')
    }
  }

  const spinning = state === 'running'
  const jackpot = state === 'done' && recs.some((r) => r.conviction >= JACKPOT_CONVICTION)

  return (
    <section className="console slot-machine" data-testid="slot-machine">
      <div className="console-bar">
        <h1 className="console-title">SLOT MACHINE</h1>
      </div>

      <div className={`slot-cabinet${jackpot ? ' is-jackpot' : ''}`}>
        {jackpot && <JackpotBanner reducedMotion={reducedMotion} />}

        <div className="reels" data-testid="reels" aria-live="polite">
          {spinning &&
            Array.from({ length: REEL_COUNT }).map((_, i) => (
              <SpinningReel key={i} index={i} reducedMotion={reducedMotion} />
            ))}

          {state === 'idle' && (
            <p className="muted slot-prompt">
              Pull the lever to spin the deterministic engine over your portfolio.
            </p>
          )}

          {state === 'done' && recs.length > 0 && (
            <div className="rec-list" data-testid="reel-results">
              {recs.map((r, i) => (
                <ReelResult
                  key={r.id}
                  index={i}
                  reducedMotion={reducedMotion}
                  jackpot={r.conviction >= JACKPOT_CONVICTION}
                >
                  <RecommendationCard rec={r} />
                </ReelResult>
              ))}
            </div>
          )}

          {state === 'done' && recs.length === 0 && abstentions.length === 0 && (
            <NoTrade reducedMotion={reducedMotion} />
          )}
        </div>

        <Lever onPull={pull} disabled={spinning} spinning={spinning} reducedMotion={reducedMotion} />
      </div>

      {error && <p className="error">{error}</p>}

      {state === 'done' && abstentions.length > 0 && <Abstentions abstentions={abstentions} />}
    </section>
  )
}

// The lever: a real, keyboard-accessible button dressed as a slot arm. Clicking
// (or Enter/Space) pulls it; the arm swings down while the reels spin.
function Lever({
  onPull,
  disabled,
  spinning,
  reducedMotion
}: {
  onPull: () => void
  disabled: boolean
  spinning: boolean
  reducedMotion: boolean
}): JSX.Element {
  return (
    <button
      type="button"
      className="lever"
      onClick={onPull}
      disabled={disabled}
      aria-label="Pull the lever"
    >
      <span className="lever-track" aria-hidden="true">
        <motion.span
          className="lever-arm"
          animate={reducedMotion ? undefined : { y: spinning ? 64 : 0 }}
          transition={{ type: 'spring', stiffness: 500, damping: 22 }}
        >
          <span className="lever-knob" />
        </motion.span>
      </span>
      <span className="lever-label">{spinning ? 'Spinning…' : 'Pull'}</span>
    </button>
  )
}

// A single reel spinning while the run is in flight: a vertical strip of glyphs
// that tumbles on a loop. Static (no transform) under reduced-motion.
function SpinningReel({
  index,
  reducedMotion
}: {
  index: number
  reducedMotion: boolean
}): JSX.Element {
  const strip = [...REEL_GLYPHS, ...REEL_GLYPHS]
  return (
    <div className="reel" data-testid="reel-spinning">
      <div className="reel-window">
        <motion.div
          className="reel-strip"
          animate={reducedMotion ? undefined : { y: ['0%', '-50%'] }}
          transition={
            reducedMotion
              ? undefined
              : {
                  duration: 0.5 + index * 0.08,
                  ease: 'linear',
                  repeat: Infinity
                }
          }
        >
          {strip.map((glyph, i) => (
            <span key={i} className="reel-glyph">
              {glyph}
            </span>
          ))}
        </motion.div>
      </div>
    </div>
  )
}

// A resolved reel: the recommendation drops in and settles, staggered per slot.
// A jackpot (High-conviction) result is badged and pulses on entry.
function ReelResult({
  index,
  reducedMotion,
  jackpot,
  children
}: {
  index: number
  reducedMotion: boolean
  jackpot: boolean
  children: React.ReactNode
}): JSX.Element {
  const className = `reel-result${jackpot ? ' jackpot' : ''}`
  const badge = jackpot ? (
    <span className="jackpot-badge" data-testid="jackpot-badge">
      ★ Jackpot
    </span>
  ) : null

  if (reducedMotion) {
    return (
      <div className={className}>
        {badge}
        {children}
      </div>
    )
  }
  return (
    <motion.div
      className={className}
      initial={{ y: -24, opacity: 0 }}
      animate={
        jackpot ? { y: 0, opacity: 1, scale: [1, 1.03, 1] } : { y: 0, opacity: 1 }
      }
      transition={{ type: 'spring', stiffness: 320, damping: 26, delay: index * 0.1 }}
    >
      {badge}
      {children}
    </motion.div>
  )
}

// AC2: a distinct payout that fires when the reels land on a High-conviction
// trade — the slot-machine's "you hit it" moment. AC4: the banner's entry is a
// finite scale/opacity pop and the ongoing pulse animates only the opacity of
// a dedicated glow layer (its box-shadow is static CSS), so the payout stays
// compositor-only and can't jank the reels. Glyphs/glow are decorative, so
// aria-hidden; the badged card carries the meaning for assistive tech.
function JackpotBanner({ reducedMotion }: { reducedMotion: boolean }): JSX.Element {
  if (reducedMotion) {
    return (
      <div className="jackpot-banner" data-testid="jackpot" role="status">
        <JackpotBannerBody />
      </div>
    )
  }
  return (
    <motion.div
      className="jackpot-banner"
      data-testid="jackpot"
      role="status"
      initial={{ scale: 0.9, opacity: 0 }}
      animate={{ scale: 1, opacity: 1 }}
      transition={{ duration: 0.35, ease: 'easeOut' }}
    >
      <motion.span
        className="jackpot-glow"
        data-testid="jackpot-glow"
        aria-hidden="true"
        animate={{ opacity: [0.35, 1] }}
        transition={{ duration: 0.6, repeat: Infinity, repeatType: 'reverse', ease: 'easeInOut' }}
      />
      <JackpotBannerBody />
    </motion.div>
  )
}

function JackpotBannerBody(): JSX.Element {
  return (
    <>
      <span className="jackpot-glyphs" aria-hidden="true">
        ★ ★ ★
      </span>
      <span className="jackpot-text">JACKPOT — high-conviction setup</span>
    </>
  )
}

// AC2: the abstain / no-result landing. Deliberately calm — a soft fade-in, no
// payout, no jarring motion — so "no trade" reads as a clean resolution.
function NoTrade({ reducedMotion }: { reducedMotion: boolean }): JSX.Element {
  const body = (
    <>
      <p className="empty-glyph">✧</p>
      <p>No trade.</p>
      <p className="muted">
        The engine abstained — no qualifying setup across your portfolio right now.
      </p>
    </>
  )
  if (reducedMotion) {
    return (
      <div className="empty-state" data-testid="engine-empty">
        {body}
      </div>
    )
  }
  return (
    <motion.div
      className="empty-state"
      data-testid="engine-empty"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.4, ease: 'easeOut' }}
    >
      {body}
    </motion.div>
  )
}

// Phase 6 AC3: the engine returns an explicit reason for every underlying it
// passed on, so "no trade" is auditable rather than a silent gap.
function Abstentions({ abstentions }: { abstentions: EngineAbstention[] }): JSX.Element {
  return (
    <div className="abstentions" data-testid="engine-abstentions">
      <p className="muted abstentions-title">
        No trade on {abstentions.length} underlying{abstentions.length > 1 ? 's' : ''} — here&apos;s
        why:
      </p>
      <ul className="abstention-list">
        {abstentions.map((a) => (
          <li key={a.symbol} className="abstention" data-testid="abstention">
            <span className="neon abstention-symbol">{a.symbol}</span>
            <span className="tag">{formatLabel(a.reason)}</span>
            <span className="muted abstention-detail">{a.detail}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}
