import type { ReactNode } from 'react'

/** task 09.6: "every screen with no data needs a designed empty state" - an icon, a heading, a
 * one-line explanation, and an optional action, rather than a single sentence of muted text. */
export function EmptyState({
  icon,
  title,
  description,
  action,
}: {
  icon: ReactNode
  title: string
  description?: string
  action?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-card bg-surface px-6 py-10 text-center">
      <span aria-hidden="true" className="text-3xl text-subtle">
        {icon}
      </span>
      <div className="flex flex-col gap-1">
        <p className="text-sm font-medium text-foreground">{title}</p>
        {description && <p className="text-xs text-subtle">{description}</p>}
      </div>
      {action}
    </div>
  )
}
