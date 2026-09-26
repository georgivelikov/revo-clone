import { useEffect, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import type { PaymentResponse } from '../api/payments'
import { PAYMENTS_FEED_QUERY_KEY } from '../hooks/usePaymentsFeed'
import { PAYMENT_COMPLETED, TRANSFER_FAILED } from './clientEvent'
import type { ClientEvent } from './clientEvent'
import { NotificationSocket } from './notificationSocket'

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

/** Mounted once at the app root - opens the socket for the lifetime of an authenticated session. */
export function useNotificationSocket(): void {
  const auth = useAuth()
  const queryClient = useQueryClient()
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
      return
    }
    const socket = new NotificationSocket(
      () => accessTokenRef.current,
      (event) => applyClientEvent(queryClient, event),
    )
    socket.connect()
    return () => socket.disconnect()
  }, [auth.isAuthenticated, queryClient])
}
