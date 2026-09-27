import { apiPost } from './http'

export type FxQuoteState = 'ACTIVE' | 'CONSUMED' | 'EXPIRED' | 'CANCELLED'

// Mirrors fx-service's QuoteResponse record (plans/phase-04-fx-multicurrency.md,
// FxQuoteController.java). feeMinor travels explicitly rather than being recomputed here, for
// the same reason the backend record gives: it must always be the exact number the FEE_INCOME
// leg will carry, taken from the same computation.
export interface QuoteResponse {
  quoteId: string
  sellCurrency: string
  buyCurrency: string
  sellMinor: number
  buyMinor: number
  midRate: string
  clientRate: string
  spreadBps: number
  feeMinor: number
  state: FxQuoteState
  expiresAt: string
}

// Mirrors CreateQuoteRequest.java: exactly one of sellMinor/buyMinor is set, for a bidirectional
// amount field - "not specified" and "specified as zero" must stay distinguishable, so null
// rather than 0 or omitted.
export interface CreateQuoteRequest {
  sellCurrency: string
  buyCurrency: string
  sellMinor: number | null
  buyMinor: number | null
}

export const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key'

// 201: the quote exists and its funds are already held - nothing about it is still in flight,
// unlike POST /api/payments' 202.
export function createQuote(
  accessToken: string,
  idempotencyKey: string,
  request: CreateQuoteRequest,
): Promise<QuoteResponse> {
  return apiPost<QuoteResponse>('/api/fx/quotes', accessToken, request, {
    [IDEMPOTENCY_KEY_HEADER]: idempotencyKey,
  })
}

// 202: the exchange will happen, but the ledger has not posted the entry yet - see
// QuoteExecutionService's own note on why this is the same "optimistic pending -> settled" shape
// Phase 03's transfer flow already made visible.
export function executeQuote(
  accessToken: string,
  idempotencyKey: string,
  quoteId: string,
): Promise<QuoteResponse> {
  return apiPost<QuoteResponse>(`/api/fx/quotes/${quoteId}/execute`, accessToken, {}, {
    [IDEMPOTENCY_KEY_HEADER]: idempotencyKey,
  })
}
