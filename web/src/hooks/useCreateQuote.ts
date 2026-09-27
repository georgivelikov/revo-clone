import { useMutation } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import type { CreateQuoteRequest, QuoteResponse } from '../api/fx'
import { createQuote } from '../api/fx'

export interface CreateQuoteVariables {
  idempotencyKey: string
  request: CreateQuoteRequest
}

// No optimistic insert here, unlike useCreatePayment: a quote has no feed of its own to patch,
// and "Get quote" already reads as synchronous to the user - the 201 IS the answer, not an
// accepted instruction to wait on.
export function useCreateQuote() {
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  return useMutation<QuoteResponse, Error, CreateQuoteVariables>({
    mutationFn: ({ idempotencyKey, request }) => createQuote(accessToken!, idempotencyKey, request),
  })
}
