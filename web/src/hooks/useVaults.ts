import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import type {
  CreateVaultRequest,
  UpdateVaultRequest,
  VaultResponse,
  VaultTransferRequest,
  VaultTransferResponse,
} from '../api/vaults'
import { createVault, fetchVaults, transferVault, updateVault } from '../api/vaults'

export const VAULTS_QUERY_KEY = ['vaults'] as const

export function useVaults() {
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  return useQuery({
    queryKey: VAULTS_QUERY_KEY,
    queryFn: () => fetchVaults(accessToken!),
    enabled: Boolean(accessToken),
  })
}

export function useCreateVault() {
  const auth = useAuth()
  const accessToken = auth.user?.access_token
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (request: CreateVaultRequest) => createVault(accessToken!, request),
    onSuccess: (vault) => {
      queryClient.setQueryData<VaultResponse[]>(VAULTS_QUERY_KEY, (old = []) => [...old, vault])
    },
  })
}

export function useUpdateVault(vaultId: string) {
  const auth = useAuth()
  const accessToken = auth.user?.access_token
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (request: UpdateVaultRequest) => updateVault(accessToken!, vaultId, request),
    onSuccess: (vault) => {
      queryClient.setQueryData<VaultResponse[]>(VAULTS_QUERY_KEY, (old = []) =>
        old.map((entry) => (entry.id === vault.id ? vault : entry)),
      )
    },
  })
}

export interface VaultTransferVariables {
  vaultId: string
  idempotencyKey: string
  request: VaultTransferRequest
}

// Unlike useCreatePayment's optimistic feed insert (plans/phase-03-payments-and-ui.md), there is
// no local vault-transfer feed to reconcile here - a vault's balance comes entirely from the
// account/ledger read-through (useAccountsWithBalances). The transfer is only *accepted* (202)
// at this point, not settled, so this invalidates the same ['ledger', 'balance'] prefix
// useExecuteQuote and useNotificationSocket already use rather than optimistically bumping a
// number that would be wrong until the underlying payment actually settles - the WebSocket
// settle event (useNotificationSocket) is what makes the eventual real update visible.
export function useVaultTransfer() {
  const auth = useAuth()
  const accessToken = auth.user?.access_token
  const queryClient = useQueryClient()

  return useMutation<VaultTransferResponse, Error, VaultTransferVariables>({
    mutationFn: ({ vaultId, idempotencyKey, request }) =>
      transferVault(accessToken!, vaultId, idempotencyKey, request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['ledger', 'balance'] })
    },
  })
}
