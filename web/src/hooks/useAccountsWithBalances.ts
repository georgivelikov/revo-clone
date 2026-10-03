import { useQueries, useQuery } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import type { AccountResponse } from '../api/accounts'
import { fetchAccounts } from '../api/accounts'
import { fetchBalance } from '../api/ledger'

export interface AccountWithBalance extends AccountResponse {
  availableMinor: number | null
  reservedMinor: number | null
  balanceError: boolean
}

export function useAccountsWithBalances() {
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  const accountsQuery = useQuery({
    queryKey: ['accounts', 'me'],
    queryFn: () => fetchAccounts(accessToken!),
    enabled: Boolean(accessToken),
  })

  const accounts = accountsQuery.data ?? []

  const balanceQueries = useQueries({
    queries: accounts.map((account) => ({
      queryKey: ['ledger', 'balance', account.ledgerAccountId],
      queryFn: () => fetchBalance(accessToken!, account.ledgerAccountId),
      enabled: Boolean(accessToken) && accountsQuery.isSuccess,
    })),
  })

  const accountsWithBalances: AccountWithBalance[] = accounts.map((account, index) => {
    const balanceQuery = balanceQueries[index]
    return {
      ...account,
      availableMinor: balanceQuery?.data?.availableMinor ?? null,
      reservedMinor: balanceQuery?.data?.reservedMinor ?? null,
      balanceError: balanceQuery?.isError ?? false,
    }
  })

  return {
    accounts: accountsWithBalances,
    isLoading: accountsQuery.isLoading,
    isError: accountsQuery.isError,
    error: accountsQuery.error,
    refetch: accountsQuery.refetch,
  }
}
