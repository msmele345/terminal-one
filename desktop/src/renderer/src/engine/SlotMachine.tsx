import { useState } from 'react'
import { motion, useReducedMotion } from 'framer-motion'
import type { EngineAbstention, Recommendation } from '../../../preload'
import { RecommendationCard, formatLabel } from './RecommendationCard'
import { ScreenState } from '../ui/ScreenState'

type RunState = 'idle' | 'running' | 'done' | 'error'

// Phase 7 AC1: the recommendation moment as a slot machine. Pulling the lever
// runs the deterministic engine (POST /api/engine/run) and the reels spin, then
// settle onto the returned top-N recommendations. No engine logic lives here —
// the result rendering reuses the Phase 4–6 RecommendationCard verbatim.
//
// The machine sits on a "casino floor": a fixed ambient backdrop (canopy
// spotlight, pit glow, drifting bokeh), a chasing-bulb marquee on the cabinet,
// light spilling onto the floor beneath it, and a coin burst on jackpots. Every
// decorative layer is aria-hidden, scoped under .slot-machine (AC5), animates
// compositor-only (AC4), and falls back to a static scene under reduced motion.

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

// The room around the machine: out-of-focus neon from the rest of the floor.
// Radial gradients give the blur for free, so the drift animates transform only.
const BOKEH_ORBS: Array<{
  size: number
  left: string
  top: string
  tone: 'magenta' | 'cyan' | 'amber'
  duration: number
  delay: number
}> = [
  { size: 340, left: '4%', top: '8%', tone: 'magenta', duration: 15, delay: -2 },
  { size: 220, left: '16%', top: '62%', tone: 'cyan', duration: 12, delay: -7 },
  { size: 280, left: '78%', top: '10%', tone: 'amber', duration: 17, delay: -4 },
  { size: 180, left: '68%', top: '70%', tone: 'magenta', duration: 11, delay: -9 },
  { size: 260, left: '88%', top: '48%', tone: 'cyan', duration: 14, delay: -1 },
  { size: 150, left: '38%', top: '4%', tone: 'amber', duration: 10, delay: -5 },
  { size: 200, left: '30%', top: '86%', tone: 'amber', duration: 16, delay: -11 }
]

// Bulbs across the cabinet's marquee rail. The chase is a shared keyframe whose
// phase is staggered by negative per-bulb delays (index-based — deterministic).
const MARQUEE_BULBS = 24

// Coins in the jackpot fountain.
const COIN_COUNT = 18

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
    <section
      className={`console slot-machine${spinning ? ' is-running' : ''}${jackpot ? ' is-jackpot' : ''}`}
      data-testid="slot-machine"
    >
      <CasinoAmbience />

      <div className="console-bar">
        <h1 className="console-title">SLOT MACHINE</h1>
      </div>

      <div className={`slot-cabinet${jackpot ? ' is-jackpot' : ''}`}>
        <Marquee />
        {jackpot && <JackpotBanner reducedMotion={reducedMotion} />}
        {jackpot && !reducedMotion && <CoinBurst />}

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

          {state === 'done' && recs.length === 0 && abstentions.length === 0 && <NoTrade />}

          {state === 'error' && error && (
            <ScreenState kind="error" title="Engine run failed" detail={error} glyph="×" />
          )}
        </div>

        <Lever onPull={pull} disabled={spinning} spinning={spinning} reducedMotion={reducedMotion} />
      </div>

      <div className="floor-spill" data-testid="floor-spill" aria-hidden="true" />

      {state === 'done' && abstentions.length > 0 && <Abstentions abstentions={abstentions} />}
    </section>
  )
}

// The casino floor the machine sits on: a fixed light scene behind everything
// (canopy spotlight from above, pit glow rising from the corners, vignette) plus
// a handful of out-of-focus neon orbs drifting slowly — the rest of the floor,
// blurred. Purely decorative, so aria-hidden and pointer-transparent; every
// animation is compositor-only transform/opacity with a CSS backstop that holds
// the scene still under prefers-reduced-motion.
function CasinoAmbience(): JSX.Element {
  return (
    <div className="casino-ambience" data-testid="casino-ambience" aria-hidden="true">
      {BOKEH_ORBS.map((orb, i) => (
        <span
          key={i}
          className={`bokeh bokeh-${orb.tone}`}
          style={{
            width: orb.size,
            height: orb.size,
            left: orb.left,
            top: orb.top,
            animationDuration: `${orb.duration}s`,
            animationDelay: `${orb.delay}s`
          }}
        />
      ))}
    </div>
  )
}

// The cabinet's marquee: a rail of warm bulbs chasing across the top edge, like
// the attract lights on a real machine. Static (fully lit) under reduced motion.
function Marquee(): JSX.Element {
  return (
    <div className="marquee" data-testid="marquee" aria-hidden="true">
      {Array.from({ length: MARQUEE_BULBS }).map((_, i) => (
        <span key={i} className="marquee-bulb" style={{ animationDelay: `${i * -90}ms` }} />
      ))}
    </div>
  )
}

// The payout flourish: a finite fountain of coins raining over the cabinet when
// the reels land a jackpot. Trajectories derive from the index (no Math.random —
// the house is deterministic), the motion is transform/opacity only, and the
// whole burst is skipped under reduced motion (the banner still announces it).
function CoinBurst(): JSX.Element {
  return (
    <div className="coin-burst" data-testid="coin-burst" aria-hidden="true">
      {Array.from({ length: COIN_COUNT }).map((_, i) => {
        const dx = (((i * 97) % 41) - 20) * 13
        const arc = -26 - (i % 4) * 12
        const dy = 200 + ((i * 53) % 13) * 24
        const spin = (i % 2 === 0 ? 1 : -1) * (160 + ((i * 29) % 6) * 40)
        return (
          <motion.span
            key={i}
            className="coin"
            initial={{ x: 0, y: 0, opacity: 1, scale: 0.5, rotate: 0 }}
            animate={{
              x: [0, dx * 0.6, dx],
              y: [0, arc, dy],
              opacity: [1, 1, 0],
              scale: [0.5, 1, 1],
              rotate: [0, spin * 0.5, spin]
            }}
            transition={{
              duration: 1.35 + ((i * 7) % 5) * 0.12,
              delay: i * 0.045,
              times: [0, 0.32, 1],
              ease: ['easeOut', 'easeIn']
            }}
          >
            {i % 3 === 0 ? '◆' : '$'}
          </motion.span>
        )
      })}
    </div>
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
function NoTrade(): JSX.Element {
  return (
    <ScreenState
      kind="empty"
      title="No trade."
      detail="The engine abstained — no qualifying setup across your portfolio right now."
      glyph="✧"
      testId="engine-empty"
    />
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
