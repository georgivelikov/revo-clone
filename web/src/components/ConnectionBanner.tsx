import type { ConnectionStatus } from '../ws/notificationSocket'

/** task 09.6: "detect a dropped socket and show a subtle reconnecting indicator." Renders
 * nothing once connected (or before a session exists at all) - a banner that is always visible
 * would stop being a *signal* the moment it stopped being noteworthy. */
export function ConnectionBanner({ status }: { status: ConnectionStatus | null }) {
  if (status !== 'connecting') {
    return null
  }
  return (
    <div
      role="status"
      aria-live="polite"
      className="flex shrink-0 items-center justify-center gap-2 bg-pending/15 px-3 py-1.5 text-xs font-medium text-pending"
    >
      <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-pending" aria-hidden="true" />
      Reconnecting…
    </div>
  )
}
