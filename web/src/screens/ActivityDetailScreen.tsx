import { useQuery } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import { useParams } from 'react-router-dom'
import { ErrorState } from '../components/ErrorState'
import { Skeleton } from '../components/Skeleton'
import { formatMoney } from '../api/format'
import { fetchPayment } from '../api/payments'

export function ActivityDetailScreen() {
  const { paymentId } = useParams()
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  const { data: payment, isLoading, isError, refetch } = useQuery({
    queryKey: ['payments', 'detail', paymentId],
    queryFn: () => fetchPayment(accessToken!, paymentId!),
    enabled: Boolean(accessToken) && Boolean(paymentId),
    retry: false,
  })

  return (
    <div className="flex flex-col gap-4 px-5 pt-8">
      <h1 className="text-xl font-semibold text-foreground">Payment</h1>

      {isLoading && (
        <div className="flex flex-col gap-3 rounded-card bg-surface p-5">
          {Array.from({ length: 4 }, (_, index) => (
            <div key={index} className="flex justify-between">
              <Skeleton className="h-3.5 w-16 rounded" />
              <Skeleton className="h-3.5 w-24 rounded" />
            </div>
          ))}
        </div>
      )}
      {isError && (
        <ErrorState
          title="Could not load this payment."
          detail="It may still be in flight - this can take a few seconds right after sending."
          onRetry={() => void refetch()}
        />
      )}
      {payment && (
        <section className="flex flex-col gap-3 rounded-card bg-surface p-5 text-sm">
          <div className="flex justify-between">
            <span className="text-muted">Status</span>
            <span className="text-foreground">{payment.status}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-muted">Amount</span>
            <span className="text-foreground">
              {formatMoney(payment.amountMinor, payment.currency)}
            </span>
          </div>
          {payment.reference && (
            <div className="flex justify-between">
              <span className="text-muted">Note</span>
              <span className="text-foreground">{payment.reference}</span>
            </div>
          )}
          {payment.failureReason && (
            <div className="flex justify-between">
              <span className="text-muted">Reason</span>
              <span className="text-negative">{payment.failureReason}</span>
            </div>
          )}
          {payment.ledgerEntryId && (
            <div className="flex justify-between">
              <span className="text-muted">Ledger entry</span>
              <span className="text-foreground">{payment.ledgerEntryId}</span>
            </div>
          )}
          <div className="flex justify-between">
            <span className="text-muted">Created</span>
            <span className="text-foreground">{new Date(payment.createdAt).toLocaleString()}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-muted">Updated</span>
            <span className="text-foreground">{new Date(payment.updatedAt).toLocaleString()}</span>
          </div>
        </section>
      )}
    </div>
  )
}
