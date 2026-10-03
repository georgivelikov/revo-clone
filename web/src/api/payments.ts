import { apiFetch, apiPost } from './http'

export type PaymentStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

// Mirrors payment-service's PaymentResponse record (plans/phase-03-payments-and-ui.md,
// PaymentController.java). failureReason/ledgerEntryId are serialized as null rather than
// omitted, so "pending, no reason yet" is distinguishable from a dropped field.
export interface PaymentResponse {
  paymentId: string
  status: PaymentStatus
  sourceAccountId: string
  targetAccountId: string
  amountMinor: number
  currency: string
  // Both null for a same-currency transfer; set together only for a cross-currency one
  // (plans/phase-04-fx-multicurrency.md task 8) - currency/amountMinor stay the source side
  // throughout, so these are what the recipient actually receives.
  targetCurrency: string | null
  targetAmountMinor: number | null
  reference: string | null
  failureReason: string | null
  ledgerEntryId: string | null
  createdAt: string
  updatedAt: string
}

// Mirrors CreatePaymentRequest.java. No initiator, no idempotency key - the initiator is the
// JWT subject and the key travels as the Idempotency-Key header, never in the body.
export interface CreatePaymentRequest {
  sourceAccountId: string
  targetAccountId: string
  amountMinor: number
  currency: string
  reference: string | null
}

export const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key'

// Always 202: the transfer is accepted, not completed (plans/phase-03-payments-and-ui.md).
// Never forward this key anywhere but here - the ledger derives its own from the paymentId.
export function createPayment(
  accessToken: string,
  idempotencyKey: string,
  request: CreatePaymentRequest,
): Promise<PaymentResponse> {
  return apiPost<PaymentResponse>('/api/payments', accessToken, request, {
    [IDEMPOTENCY_KEY_HEADER]: idempotencyKey,
  })
}

// Feed of every payment touching one of the caller's own accounts, newest first - see
// PaymentQueryService (task 09.6; the endpoint was documented since task 03.1 but, until this
// task's Activity screen and the poll fallback from task 03.10 both needed it, nothing had ever
// actually called it, so the gap in payment-service went unnoticed).
export function fetchPayments(accessToken: string): Promise<PaymentResponse[]> {
  return apiFetch<PaymentResponse[]>('/api/payments', accessToken)
}

export function fetchPayment(accessToken: string, paymentId: string): Promise<PaymentResponse> {
  return apiFetch<PaymentResponse>(`/api/payments/${paymentId}`, accessToken)
}
