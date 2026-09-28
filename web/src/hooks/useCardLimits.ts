import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import type { CardLimitResponse } from '../api/cards'
import { fetchCardLimits, removeCardLimit, upsertCardLimit } from '../api/cards'

function limitsQueryKey(cardId: string) {
  return ['cards', cardId, 'limits'] as const
}

export function useCardLimits(cardId: string | null) {
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  return useQuery({
    queryKey: limitsQueryKey(cardId ?? ''),
    queryFn: () => fetchCardLimits(accessToken!, cardId!),
    enabled: Boolean(accessToken) && Boolean(cardId),
  })
}

export function useUpsertCardLimit(cardId: string) {
  const auth = useAuth()
  const accessToken = auth.user?.access_token
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ period, amountMinor }: { period: CardLimitResponse['period']; amountMinor: number }) =>
      upsertCardLimit(accessToken!, cardId, period, amountMinor),
    onSuccess: (limit) => {
      queryClient.setQueryData<CardLimitResponse[]>(limitsQueryKey(cardId), (old = []) => [
        ...old.filter((entry) => entry.period !== limit.period),
        limit,
      ])
    },
  })
}

export function useRemoveCardLimit(cardId: string) {
  const auth = useAuth()
  const accessToken = auth.user?.access_token
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (period: CardLimitResponse['period']) => removeCardLimit(accessToken!, cardId, period),
    onSuccess: (_void, period) => {
      queryClient.setQueryData<CardLimitResponse[]>(limitsQueryKey(cardId), (old = []) =>
        old.filter((entry) => entry.period !== period),
      )
    },
  })
}
