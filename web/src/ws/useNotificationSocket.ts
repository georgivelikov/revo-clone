import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import type { PaymentResponse } from '../api/payments'
import { recordDevEvent } from '../dev/eventLog'
import { RATE_TICKS_QUERY_KEY } from '../hooks/useRateTicks'
import { PAYMENTS_FEED_QUERY_KEY } from '../hooks/usePaymentsFeed'
import { PAYMENT_COMPLETED, TRANSFER_FAILED } from './clientEvent'
import type { ClientEvent } from './clientEvent'
import type { ConnectionStatus } from './notificationSocket'
import { NotificationSocket } from './notificationSocket'
import type { RateTickFrame } from './rateTick'

function patchPaymentStatus(
  queryClient: QueryClient,
  paymentId: string,
  status: PaymentResponse['status'],
  failureReason: string | null,
): void {
  // No-ops if paymentId isn't in the cache yet (e.g. the socket frame beat the 202 response) -
  // the mutation's own onSuccess handler will insert the real row, already in its final status.
  queryClient.setQueryData<PaymentResponse[]>(PAYMENTS_FEED_QUERY_KEY, (old) =>
    old?.map((entry) =>
      entry.paymentId === paymentId
        ? { ...entry, status, failureReason: failureReason ?? entry.failureReason }
        : entry,
    ),
  )
}

// Steps 4 of "Optimistic UI with reconciliation" (plans/phase-03-payments-and-ui.md): the
// WebSocket event flips a PENDING row to COMPLETED (settle) or FAILED (roll back, show the
// reason). Step 5, the 5s polling fallback for when no event arrives, is usePaymentPolling -
// deliberately separate, since it reacts to the same query cache rather than to this socket, so
// it keeps working even if the socket never connects at all.
function applyClientEvent(queryClient: QueryClient, event: ClientEvent): void {
  if (!event.paymentId) {
    return
  }
  if (event.type === PAYMENT_COMPLETED) {
    patchPaymentStatus(queryClient, event.paymentId, 'COMPLETED', null)
    // Both parties' balances moved; each browser only knows its own accounts, but invalidating
    // unconditionally is simpler and cheaper than inspecting which account ids are ours.
    queryClient.invalidateQueries({ queryKey: ['ledger', 'balance'] })
  } else if (event.type === TRANSFER_FAILED) {
    // The failure reason travels in `body` here, not `reason` - see ClientEvent.of, which is
    // what carries a persisted TRANSFER_FAILED notification (body holds the same text stored in
    // Notification.transferFailed).
    patchPaymentStatus(queryClient, event.paymentId, 'FAILED', event.body)
  }
}

// Task 9's live rate feed: the socket, not react-query's fetch, is the only source for this
// data (there is no GET for "the current rate"), so a tick simply merges into the cache under
// its own pair - see useRateTicks for why that query never fetches on its own.
function applyRateTick(queryClient: QueryClient, tick: RateTickFrame): void {
  queryClient.setQueryData<Record<string, RateTickFrame>>(RATE_TICKS_QUERY_KEY, (old = {}) => ({
    ...old,
    [tick.pair]: tick,
  }))
}

/** Mounted once at the app root - opens the socket for the lifetime of an authenticated session.
 * Returns the current connection status so AppShell can show a subtle reconnecting indicator
 * (task 09.6) without a second socket connection of its own - `null` while unauthenticated, when
 * no socket exists to report on at all. */
export function useNotificationSocket(): ConnectionStatus | null {
  const auth = useAuth()
  const queryClient = useQueryClient()
  // Initialized to 'connecting' directly, rather than set from inside the effect below:
  // mounting this hook and starting the first connection attempt happen together, so the
  // initial state already matches the only thing the effect would otherwise set it to on its
  // first run. A later real drop still updates this - from the socket's own onWebSocketClose
  // callback, an event, not a synchronous effect-body setState.
  const [status, setStatus] = useState<ConnectionStatus | null>('connecting')
  // A ref, not a dependency: react-oidc-context silently rotates the access token well before
  // it expires, and reconnecting the socket on every rotation would fight its own backoff for
  // no reason. beforeConnect reads this at the moment it mints each ticket, so a reconnect -
  // whether backed-off or a fresh login - always uses whatever token is current then.
  const accessToken = auth.user?.access_token
  const accessTokenRef = useRef(accessToken)
  useEffect(() => {
    accessTokenRef.current = accessToken
  }, [accessToken])

  useEffect(() => {
    if (!auth.isAuthenticated) {
      // No setState here - the returned value below is derived straight from
      // auth.isAuthenticated for this case, not from state.
      return
    }
    const socket = new NotificationSocket(
      () => accessTokenRef.current,
      (event) => {
        // The check happens at the call site, not inside recordDevEvent, so a production
        // build's dead-code elimination can drop the dev-drawer module from the bundle entirely.
        if (import.meta.env.DEV) {
          recordDevEvent(event)
        }
        applyClientEvent(queryClient, event)
      },
      (tick) => applyRateTick(queryClient, tick),
      setStatus,
    )
    socket.connect()
    return () => socket.disconnect()
  }, [auth.isAuthenticated, queryClient])

  return auth.isAuthenticated ? status : null
}
