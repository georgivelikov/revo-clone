import { useQuery } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import { fetchPayments } from '../api/payments'

export const PAYMENTS_FEED_QUERY_KEY = ['payments', 'feed'] as const

// Shared across HomeScreen's recent-activity section, ActivityScreen and the optimistic insert
// in useCreatePayment - they all read/write this one cache entry, which is what lets a payment
// initiated on the Transfer screen show up immediately elsewhere without a refetch.
export function usePaymentsFeed() {
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  return useQuery({
    queryKey: PAYMENTS_FEED_QUERY_KEY,
    queryFn: () => fetchPayments(accessToken!),
    enabled: Boolean(accessToken),
    retry: false,
  })
}
