import { apiFetch } from './http'

// Mirrors analytics-service's Category.ALL (categorisation/Category.java) - the fixed twelve-
// category set from plans/phase-06-vaults-analytics.md's "Categorisation" section, in that
// section's own listed order. Used both for the donut's fixed slice order (see
// charts/categoryColors.ts) and to validate a drill-down request before it round-trips.
export const CATEGORIES = [
  'Groceries',
  'Restaurants',
  'Transport',
  'Shopping',
  'Entertainment',
  'Bills',
  'Health',
  'Travel',
  'Transfers',
  'Cash',
  'Income',
  'Other',
] as const

// Mirrors CategorySpend.java. Money is minor units + an ISO currency code, never summed across
// currencies here - see MonthlySummary's own comment.
export interface CategorySpend {
  category: string
  currency: string
  totalMinor: number
  txnCount: number
}

// Mirrors MonthlySummary.java. `categories` already comes pre-broken-down per (category,
// currency) - a user who spent in two currencies this month gets two rows for the same
// category, never one summed figure. `month` is always the first of the month.
export interface MonthlySummary {
  month: string
  categories: CategorySpend[]
}

// Mirrors DrillDownTransaction.java.
export interface DrillDownTransaction {
  authorizationId: string
  merchantName: string
  amountMinor: number
  currency: string
  settledAt: string
}

// Mirrors TrendPoint.java. One row per (month, currency) - never summed across currencies.
export interface TrendPoint {
  month: string
  currency: string
  totalMinor: number
  txnCount: number
}

// Mirrors TopMerchant.java.
export interface TopMerchant {
  merchantName: string
  currency: string
  totalMinor: number
  txnCount: number
}

// Mirrors BudgetResponse.java (web/BudgetController.java). alertedAt80/100 are display-inert
// here - the dashboard only reads the limit, never the alert bookkeeping.
export interface BudgetResponse {
  id: string
  category: string
  month: string
  limitMinor: number
  currency: string
  alertedAt80: string | null
  alertedAt100: string | null
}

// Mirrors UpsertBudgetRequest.java.
export interface UpsertBudgetRequest {
  category: string
  month: string
  limitMinor: number
  currency: string
}

// `month`/`asOf` are always a plain ISO date string (YYYY-MM-DD) - the backend accepts any day
// within the target month and normalises to the first itself (AnalyticsQueryController).

export function fetchMonthlySummary(accessToken: string, month: string): Promise<MonthlySummary> {
  return apiFetch<MonthlySummary>(`/api/analytics/summary?month=${month}`, accessToken)
}

export function fetchCategoryDrillDown(
  accessToken: string,
  category: string,
  month: string,
): Promise<DrillDownTransaction[]> {
  return apiFetch<DrillDownTransaction[]>(
    `/api/analytics/categories/${encodeURIComponent(category)}/transactions?month=${month}`,
    accessToken,
  )
}

export function fetchTrend(accessToken: string, asOf: string): Promise<TrendPoint[]> {
  return apiFetch<TrendPoint[]>(`/api/analytics/trend?asOf=${asOf}`, accessToken)
}

export function fetchTopMerchants(
  accessToken: string,
  month: string,
  limit: number,
): Promise<TopMerchant[]> {
  return apiFetch<TopMerchant[]>(`/api/analytics/merchants/top?month=${month}&limit=${limit}`, accessToken)
}

export function fetchBudgets(accessToken: string): Promise<BudgetResponse[]> {
  return apiFetch<BudgetResponse[]>('/api/analytics/budgets', accessToken)
}

export function upsertBudget(accessToken: string, request: UpsertBudgetRequest): Promise<BudgetResponse> {
  return apiFetch<BudgetResponse>('/api/analytics/budgets', accessToken, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
}
