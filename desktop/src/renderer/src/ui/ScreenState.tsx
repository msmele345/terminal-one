import type { ReactNode } from 'react'

type ScreenStateKind = 'empty' | 'error'

export function ScreenState({
  kind,
  title,
  detail,
  glyph,
  children,
  testId
}: {
  kind: ScreenStateKind
  title: string
  detail?: string
  glyph?: string
  children?: ReactNode
  testId?: string
}): JSX.Element {
  return (
    <div
      className={`screen-state screen-state-${kind}`}
      role={kind === 'error' ? 'alert' : 'status'}
      data-testid={testId}
    >
      <p className="screen-state-glyph" aria-hidden="true">
        {glyph ?? (kind === 'error' ? '!' : '▦')}
      </p>
      <p className="screen-state-title">{title}</p>
      {detail && <p className="muted screen-state-detail">{detail}</p>}
      {children && <div className="screen-state-actions">{children}</div>}
    </div>
  )
}
