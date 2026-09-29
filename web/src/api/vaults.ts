import { apiFetch, apiPost } from './http'

// Mirrors analytics-service's TransferDirection enum (vault package): IN is wallet -> vault,
// OUT is vault -> wallet.
export type VaultTransferDirection = 'IN' | 'OUT'

// Mirrors analytics-service's VaultResponse record. accountId is account-service's id for the
// vault's backing VAULT-type account - the same id that shows up in GET /api/accounts/me, which
// is how the vault's actual balance is read (see useVaults.ts / VaultsScreen.tsx).
export interface VaultResponse {
  id: string
  userId: string
  accountId: string
  name: string
  currency: string
  goalMinor: number | null
  roundupEnabled: boolean
  roundupSourceCardId: string | null
  createdAt: string
}

// Mirrors CreateVaultRequest.java.
export interface CreateVaultRequest {
  name: string
  currency: string
  goalMinor: number | null
  roundupEnabled: boolean
  roundupSourceCardId: string | null
}

// Mirrors UpdateVaultRequest.java - goal and round-up settings only, name/currency/account never
// change once a vault exists.
export interface UpdateVaultRequest {
  goalMinor: number | null
  roundupEnabled: boolean
  roundupSourceCardId: string | null
}

// Mirrors VaultTransferRequest.java.
export interface VaultTransferRequest {
  direction: VaultTransferDirection
  walletAccountId: string
  amountMinor: number
  currency: string
}

// Mirrors VaultTransferResponse.java. status is payment-service's PaymentStatus, almost always
// "PENDING" at this point since the endpoint answers 202 (accepted, not completed).
export interface VaultTransferResponse {
  paymentId: string
  status: string
}

export const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key'

export function fetchVaults(accessToken: string): Promise<VaultResponse[]> {
  return apiFetch<VaultResponse[]>('/api/analytics/vaults', accessToken)
}

export function createVault(accessToken: string, request: CreateVaultRequest): Promise<VaultResponse> {
  return apiPost<VaultResponse>('/api/analytics/vaults', accessToken, request)
}

export function updateVault(
  accessToken: string,
  vaultId: string,
  request: UpdateVaultRequest,
): Promise<VaultResponse> {
  return apiFetch<VaultResponse>(`/api/analytics/vaults/${vaultId}`, accessToken, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
}

// Always 202: the transfer has been accepted by payment-service, not completed - the vault's
// own account balance (read through GET /api/accounts/me + the ledger balance) only reflects it
// once the underlying payment settles.
export function transferVault(
  accessToken: string,
  vaultId: string,
  idempotencyKey: string,
  request: VaultTransferRequest,
): Promise<VaultTransferResponse> {
  return apiPost<VaultTransferResponse>(`/api/analytics/vaults/${vaultId}/transfer`, accessToken, request, {
    [IDEMPOTENCY_KEY_HEADER]: idempotencyKey,
  })
}
