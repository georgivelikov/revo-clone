import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import { ApiError } from '../api/http'
import type { CreatePaymentRequest, PaymentResponse } from '../api/payments'
import { createPayment } from '../api/payments'
import { PAYMENTS_FEED_QUERY_KEY } from './usePaymentsFeed'

export interface InitiateTransferVariables {
  idempotencyKey: string
  request: CreatePaymentRequest
}

const OPTIMISTIC_ID_PREFIX = 'optimistic:'

export function isOptimisticPaymentId(paymentId: string): boolean {
  return paymentId.startsWith(OPTIMISTIC_ID_PREFIX)
}

interface MutationContext {
  previousFeed: PaymentResponse[] | undefined
  optimisticId: string
}

// Steps 1-3 of the "Optimistic UI with reconciliation" recipe
// (plans/phase-03-payments-and-ui.md): the caller generates the Idempotency-Key, this hook
// inserts a PENDING row before the network call resolves, and either confirms it with the real
// response or rolls it back. Steps 4-5 (WebSocket settle/fail, polling fallback) are task 03.10.
export function useCreatePayment() {
  const auth = useAuth()
  const accessToken = auth.user?.access_token
  const queryClient = useQueryClient()

  return useMutation<PaymentResponse, Error, InitiateTransferVariables, MutationContext>({
    mutationFn: ({ idempotencyKey, request }) =>
      createPayment(accessToken!, idempotencyKey, request),

    onMutate: async ({ idempotencyKey, request }) => {
      await queryClient.cancelQueries({ queryKey: PAYMENTS_FEED_QUERY_KEY })
      const previousFeed = queryClient.getQueryData<PaymentResponse[]>(PAYMENTS_FEED_QUERY_KEY)

      // The real id is server-generated (Payment.id) and only arrives with the 202 response, so
      // the temporary row is keyed by the idempotency key the caller already committed to.
      const optimisticId = `${OPTIMISTIC_ID_PREFIX}${idempotencyKey}`
      const now = new Date().toISOString()
      const optimisticPayment: PaymentResponse = {
        paymentId: optimisticId,
        status: 'PENDING',
        sourceAccountId: request.sourceAccountId,
        targetAccountId: request.targetAccountId,
        amountMinor: request.amountMinor,
        currency: request.currency,
        // Unknown until the response arrives - a client can't price a cross-currency transfer
        // itself, so the optimistic row never claims to know the recipient's side.
        targetCurrency: null,
        targetAmountMinor: null,
        reference: request.reference,
        failureReason: null,
        ledgerEntryId: null,
        createdAt: now,
        updatedAt: now,
      }
      queryClient.setQueryData<PaymentResponse[]>(PAYMENTS_FEED_QUERY_KEY, (old = []) => [
        optimisticPayment,
        ...old,
      ])

      return { previousFeed, optimisticId }
    },

    // Reconcile by payment id, not array position (plans/phase-03-payments-and-ui.md's
    // gotcha list): find whichever entry has the temporary id and replace it in place.
    onSuccess: (payment, _variables, context) => {
      queryClient.setQueryData<PaymentResponse[]>(PAYMENTS_FEED_QUERY_KEY, (old = []) =>
        old.map((entry) => (entry.paymentId === context.optimisticId ? payment : entry)),
      )
    },

    onError: (error, _variables, context) => {
      if (!context) {
        return
      }
      // 409 IN_PROGRESS means a concurrent duplicate of the same key is already being handled
      // by the gateway (task 03.5) - the transfer was never rejected, so the pending row stays.
      // Anything else (422 rejection, network failure, ...) means it never became a payment.
      if (error instanceof ApiError && error.status === 409) {
        return
      }
      queryClient.setQueryData(PAYMENTS_FEED_QUERY_KEY, context.previousFeed)
    },
  })
}
