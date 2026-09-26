import type { ClientEvent } from '../ws/clientEvent'

// Backs the dev drawer (plans/phase-03-payments-and-ui.md: "a slide-over showing the live
// event stream for the current user with correlation ids... the best debugging tool you will
// have for the rest of the project"). A plain module-scope ring buffer rather than a query-cache
// entry or a Zustand store: it needs to survive route changes exactly like the socket connection
// does, and useSyncExternalStore is the least machinery that gives a React 19 component a live
// view over module state that also changes from outside React (the socket's onEvent callback).
export interface DevLogEntry {
  seq: number
  receivedAt: string
  event: ClientEvent
}

const MAX_ENTRIES = 200

let entries: DevLogEntry[] = []
let nextSeq = 0
const listeners = new Set<() => void>()

export function recordDevEvent(event: ClientEvent): void {
  entries = [{ seq: nextSeq++, receivedAt: new Date().toISOString(), event }, ...entries].slice(
    0,
    MAX_ENTRIES,
  )
  for (const listener of listeners) {
    listener()
  }
}

export function clearDevEvents(): void {
  entries = []
  for (const listener of listeners) {
    listener()
  }
}

export function subscribeDevEvents(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function getDevEventsSnapshot(): DevLogEntry[] {
  return entries
}
