import { apiFetch, apiPost } from './http'

// Mirrors card-service's CardResponse record.
export interface CardResponse {
  id: string
  userId: string
  accountId: string
  panLast4: string
  expiryMonth: number
  expiryYear: number
  type: string
  state: 'ACTIVE' | 'FROZEN' | 'CANCELLED'
  label: string | null
  createdAt: string
}

export interface IssueCardRequest {
  accountId: string
  type: 'VIRTUAL' | 'PHYSICAL'
  label: string | null
  pin: string
}

export interface RevealCardResponse {
  pan: string
  cvv: string
  expiryMonth: number
  expiryYear: number
}

// Mirrors card-service's CardLimitResponse record. period is one of LimitPeriod's names.
export interface CardLimitResponse {
  period: 'PER_TRANSACTION' | 'DAILY' | 'MONTHLY'
  amountMinor: number
  currency: string
}

// Mirrors card-service's CardAuthorizationResponse record.
export interface CardAuthorizationResponse {
  networkRef: string
  merchantName: string
  merchantMcc: string
  merchantCountry: string
  amountMinor: number
  currency: string
  billingMinor: number | null
  billingCurrency: string | null
  state: 'APPROVED' | 'DECLINED' | 'SETTLED' | 'PARTIALLY_SETTLED' | 'REVERSED' | 'EXPIRED'
  declineReason: string | null
  settledMinor: number | null
  excessMinor: number | null
  authorizedAt: string
  settledAt: string | null
}

export function fetchCards(accessToken: string): Promise<CardResponse[]> {
  return apiFetch<CardResponse[]>('/api/cards', accessToken)
}

export function issueCard(accessToken: string, request: IssueCardRequest): Promise<CardResponse> {
  return apiPost<CardResponse>('/api/cards', accessToken, request)
}

export function revealCard(accessToken: string, cardId: string, pin: string): Promise<RevealCardResponse> {
  return apiPost<RevealCardResponse>(`/api/cards/${cardId}/reveal`, accessToken, { pin })
}

export function freezeCard(accessToken: string, cardId: string): Promise<CardResponse> {
  return apiPost<CardResponse>(`/api/cards/${cardId}/freeze`, accessToken, undefined)
}

export function unfreezeCard(accessToken: string, cardId: string): Promise<CardResponse> {
  return apiPost<CardResponse>(`/api/cards/${cardId}/unfreeze`, accessToken, undefined)
}

export function fetchCardLimits(accessToken: string, cardId: string): Promise<CardLimitResponse[]> {
  return apiFetch<CardLimitResponse[]>(`/api/cards/${cardId}/limits`, accessToken)
}

export function upsertCardLimit(
  accessToken: string,
  cardId: string,
  period: CardLimitResponse['period'],
  amountMinor: number,
): Promise<CardLimitResponse> {
  return apiFetch<CardLimitResponse>(`/api/cards/${cardId}/limits/${period}`, accessToken, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amountMinor }),
  })
}

export function removeCardLimit(
  accessToken: string,
  cardId: string,
  period: CardLimitResponse['period'],
): Promise<void> {
  return apiFetch<void>(`/api/cards/${cardId}/limits/${period}`, accessToken, { method: 'DELETE' })
}

export function fetchCardAuthorizations(
  accessToken: string,
  cardId: string,
): Promise<CardAuthorizationResponse[]> {
  return apiFetch<CardAuthorizationResponse[]>(`/api/cards/${cardId}/authorizations`, accessToken)
}
