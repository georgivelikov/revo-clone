import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import type { QuoteResponse } from '../api/fx'
import { executeQuote } from '../api/fx'

export interface ExecuteQuoteVariables {
  idempotencyKey: string
  quoteId: string
}

// 202: the exchange is CONSUMED here but not yet settled - the ledger captures the reservation
// and posts the entry off the back of the outbox event this triggers. Balances are therefore
// invalidated rather than patched optimistically; there is no feed row to reconcile against, the
// way useCreatePayment reconciles a payment's own status.
export function useExecuteQuote() {
  const auth = useAuth()
  const accessToken = auth.user?.access_token
  const queryClient = useQueryClient()

  return useMutation<QuoteResponse, Error, ExecuteQuoteVariables>({
    mutationFn: ({ idempotencyKey, quoteId }) => executeQuote(accessToken!, idempotencyKey, quoteId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ledger', 'balance'] })
    },
  })
}
