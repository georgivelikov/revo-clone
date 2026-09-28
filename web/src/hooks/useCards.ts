import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import type { CardResponse, IssueCardRequest } from '../api/cards'
import { fetchCards, issueCard } from '../api/cards'

export const CARDS_QUERY_KEY = ['cards'] as const

export function useCards() {
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  return useQuery({
    queryKey: CARDS_QUERY_KEY,
    queryFn: () => fetchCards(accessToken!),
    enabled: Boolean(accessToken),
  })
}

export function useIssueCard() {
  const auth = useAuth()
  const accessToken = auth.user?.access_token
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (request: IssueCardRequest) => issueCard(accessToken!, request),
    onSuccess: (card) => {
      queryClient.setQueryData<CardResponse[]>(CARDS_QUERY_KEY, (old = []) => [card, ...old])
    },
  })
}
