import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import type { CardResponse } from '../api/cards'
import { freezeCard, unfreezeCard } from '../api/cards'
import { CARDS_QUERY_KEY } from './useCards'

interface MutationContext {
  previousCards: CardResponse[] | undefined
}

function replaceCardState(
  queryClient: ReturnType<typeof useQueryClient>,
  cardId: string,
  state: CardResponse['state'],
) {
  queryClient.setQueryData<CardResponse[]>(CARDS_QUERY_KEY, (old = []) =>
    old.map((card) => (card.id === cardId ? { ...card, state } : card)),
  )
}

// plans/phase-05-cards.md's Card screen: "Freeze toggle with an instant, animated state change."
// Optimistic in both directions, same "reconcile by id" rule useCreatePayment follows for
// payments - the toggle flips the instant it's tapped and rolls back only if the server refuses.
function useToggle(
  mutationFn: (accessToken: string, cardId: string) => Promise<CardResponse>,
  optimisticState: CardResponse['state'],
) {
  const auth = useAuth()
  const accessToken = auth.user?.access_token
  const queryClient = useQueryClient()

  return useMutation<CardResponse, Error, string, MutationContext>({
    mutationFn: (cardId) => mutationFn(accessToken!, cardId),

    onMutate: async (cardId) => {
      await queryClient.cancelQueries({ queryKey: CARDS_QUERY_KEY })
      const previousCards = queryClient.getQueryData<CardResponse[]>(CARDS_QUERY_KEY)
      replaceCardState(queryClient, cardId, optimisticState)
      return { previousCards }
    },

    onSuccess: (card) => {
      queryClient.setQueryData<CardResponse[]>(CARDS_QUERY_KEY, (old = []) =>
        old.map((entry) => (entry.id === card.id ? card : entry)),
      )
    },

    onError: (_error, _cardId, context) => {
      if (context) {
        queryClient.setQueryData(CARDS_QUERY_KEY, context.previousCards)
      }
    },
  })
}

export function useFreezeCard() {
  return useToggle(freezeCard, 'FROZEN')
}

export function useUnfreezeCard() {
  return useToggle(unfreezeCard, 'ACTIVE')
}
