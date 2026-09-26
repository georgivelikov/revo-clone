import { useState, useSyncExternalStore } from 'react'
import type { DevLogEntry } from './eventLog'
import { clearDevEvents, getDevEventsSnapshot, subscribeDevEvents } from './eventLog'

function useDevEvents(): DevLogEntry[] {
  return useSyncExternalStore(subscribeDevEvents, getDevEventsSnapshot)
}

function eventColor(type: string): string {
  if (type === 'TRANSFER_FAILED') {
    return 'text-negative'
  }
  if (type === 'PAYMENT_COMPLETED' || type === 'MONEY_IN') {
    return 'text-positive'
  }
  if (type === 'PAYMENT_PENDING') {
    return 'text-pending'
  }
  return 'text-foreground'
}

function DevLogRow({ entry }: { entry: DevLogEntry }) {
  const { event } = entry
  return (
    <li className="flex flex-col gap-1 rounded-control border border-border bg-surface-raised p-3 text-xs">
      <div className="flex items-center justify-between">
        <span className={`font-semibold ${eventColor(event.type)}`}>{event.type}</span>
        <span className="text-subtle">{new Date(entry.receivedAt).toLocaleTimeString()}</span>
      </div>
      <div className="flex justify-between gap-2 text-muted">
        <span className="truncate">correlationId</span>
        <span className="truncate font-mono text-foreground">{event.correlationId}</span>
      </div>
      {event.paymentId && (
        <div className="flex justify-between gap-2 text-muted">
          <span className="truncate">paymentId</span>
          <span className="truncate font-mono text-foreground">{event.paymentId}</span>
        </div>
      )}
      {event.body && <p className="text-muted">{event.body}</p>}
    </li>
  )
}

/**
 * Dev-only slide-over over the live WebSocket event stream (plans/phase-03-payments-and-ui.md).
 * Mounted unconditionally by the app root, but renders nothing outside a dev build - the check
 * happens here, once, rather than at every call site that might otherwise need to know.
 */
export function DevDrawer() {
  const [open, setOpen] = useState(false)
  const events = useDevEvents()

  if (!import.meta.env.DEV) {
    return null
  }

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label="Open dev event drawer"
        className="fixed bottom-24 right-4 z-40 flex h-10 w-10 items-center justify-center rounded-full border border-border bg-surface-raised text-lg shadow-lg"
      >
        🛠
      </button>

      {open && (
        <div className="fixed inset-0 z-50 flex justify-end">
          <button
            type="button"
            aria-label="Close dev event drawer"
            className="absolute inset-0 bg-black/60"
            onClick={() => setOpen(false)}
          />
          <div className="relative flex h-full w-full max-w-xs flex-col gap-3 overflow-y-auto border-l border-border bg-surface p-4">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-semibold text-foreground">Event stream</h2>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={clearDevEvents}
                  className="text-xs text-muted underline"
                >
                  Clear
                </button>
                <button
                  type="button"
                  onClick={() => setOpen(false)}
                  className="text-xs text-muted underline"
                >
                  Close
                </button>
              </div>
            </div>

            {events.length === 0 && (
              <p className="text-xs text-subtle">
                No events yet. They will appear here as they arrive over the WebSocket.
              </p>
            )}

            <ul className="flex flex-col gap-2">
              {events.map((entry) => (
                <DevLogRow key={entry.seq} entry={entry} />
              ))}
            </ul>
          </div>
        </div>
      )}
    </>
  )
}
