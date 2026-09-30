import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import type { UpsertBudgetRequest } from '../api/analytics'
import {
  fetchBudgets,
  fetchCategoryDrillDown,
  fetchMonthlySummary,
  fetchTopMerchants,
  fetchTrend,
  upsertBudget,
} from '../api/analytics'

const DEFAULT_TOP_MERCHANTS_LIMIT = 10

export function useMonthlySummary(month: string) {
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  return useQuery({
    queryKey: ['analytics', 'summary', month],
    queryFn: () => fetchMonthlySummary(accessToken!, month),
    enabled: Boolean(accessToken),
  })
}

// `category` is null while nothing is drilled into - disabled rather than fetching everything
// and filtering client-side, since the drill-down endpoint is exactly this shape server-side.
export function useCategoryDrillDown(category: string | null, month: string) {
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  return useQuery({
    queryKey: ['analytics', 'categories', category, 'transactions', month],
    queryFn: () => fetchCategoryDrillDown(accessToken!, category!, month),
    enabled: Boolean(accessToken) && category !== null,
  })
}

// `asOf` picks the trailing 6-month window server-side (AnalyticsQueryController.TREND_MONTHS) -
// this hook does not need its own month-count parameter.
export function useTrend(asOf: string) {
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  return useQuery({
    queryKey: ['analytics', 'trend', asOf],
    queryFn: () => fetchTrend(accessToken!, asOf),
    enabled: Boolean(accessToken),
  })
}

export function useTopMerchants(month: string, limit: number = DEFAULT_TOP_MERCHANTS_LIMIT) {
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  return useQuery({
    queryKey: ['analytics', 'merchants', 'top', month, limit],
    queryFn: () => fetchTopMerchants(accessToken!, month, limit),
    enabled: Boolean(accessToken),
  })
}

// GET /api/analytics/budgets returns a user's full budget list, unfiltered by month (06.8's own
// note on BudgetController) - the dashboard joins it against the selected month's summary
// client-side by (category, currency, month), rather than adding a month-filtered endpoint.
export function useBudgets() {
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  return useQuery({
    queryKey: ['analytics', 'budgets'],
    queryFn: () => fetchBudgets(accessToken!),
    enabled: Boolean(accessToken),
  })
}

export function useUpsertBudget() {
  const auth = useAuth()
  const accessToken = auth.user?.access_token
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (request: UpsertBudgetRequest) => upsertBudget(accessToken!, request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['analytics', 'budgets'] })
    },
  })
}
