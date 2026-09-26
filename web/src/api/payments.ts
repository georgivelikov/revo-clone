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

// GET /api/payments and GET /api/payments/{id} are documented in payment-service's endpoint
// table but are not implemented server-side as of task 03.9 - only POST exists so far. Calling
// them will 404 until a later task adds them; callers treat that as "no feed yet", not a crash.
export function fetchPayments(accessToken: string): Promise<PaymentResponse[]> {
  return apiFetch<PaymentResponse[]>('/api/payments', accessToken)
}

export function fetchPayment(accessToken: string, paymentId: string): Promise<PaymentResponse> {
  return apiFetch<PaymentResponse>(`/api/payments/${paymentId}`, accessToken)
}
