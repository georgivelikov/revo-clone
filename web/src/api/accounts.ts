import { apiFetch } from './http'

// Mirrors account-service's AccountResponse record. `balance` stays null there until the
// ledger read-through below fills it in - see AccountResponse.from() on the backend.
export interface AccountResponse {
  id: string
  userId: string
  currency: string
  type: string
  name: string
  state: string
  ledgerAccountId: string
  balance: number | null
  createdAt: string
}

export function fetchAccounts(accessToken: string): Promise<AccountResponse[]> {
  return apiFetch<AccountResponse[]>('/api/accounts/me', accessToken)
}
