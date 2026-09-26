import { apiFetch } from './http'

// Mirrors ledger-service's BalanceResponse record, reached through the gateway's
// /api/ledger/** -> /internal/ledger/** read-through (plans/phase-03-payments-and-ui.md).
export interface BalanceResponse {
  ledgerAccountId: string
  availableMinor: number
  reservedMinor: number
  currency: string
}

export function fetchBalance(
  accessToken: string,
  ledgerAccountId: string,
): Promise<BalanceResponse> {
  return apiFetch<BalanceResponse>(`/api/ledger/accounts/${ledgerAccountId}/balance`, accessToken)
}
