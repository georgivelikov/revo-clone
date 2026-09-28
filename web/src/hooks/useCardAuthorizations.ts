import { useQuery } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import type { CardAuthorizationResponse } from '../api/cards'
import { fetchCardAuthorizations } from '../api/cards'

const POLL_INTERVAL_MS = 5_000

const PENDING_STATES: ReadonlySet<CardAuthorizationResponse['state']> = new Set(['APPROVED'])

export function isPending(authorization: CardAuthorizationResponse): boolean {
  return PENDING_STATES.has(authorization.state)
}

/**
 * plans/phase-05-cards.md's Card screen: "Per-card spend feed with pending clearly distinct from
 * settled." Settlement here always arrives from the simulator's own controllable clock
 * (task 05.8), not a push this project wires to the browser, so a plain poll is what actually
 * notices the transition - same 5s interval `usePaymentPolling` falls back to when its own
 * WebSocket has nothing to say.
 */
export function useCardAuthorizations(cardId: string | null) {
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  return useQuery({
    queryKey: ['cards', cardId, 'authorizations'],
    queryFn: () => fetchCardAuthorizations(accessToken!, cardId!),
    enabled: Boolean(accessToken) && Boolean(cardId),
    refetchInterval: POLL_INTERVAL_MS,
  })
}
