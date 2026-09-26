import { useQuery } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import { useParams } from 'react-router-dom'
import { formatMoney } from '../api/format'
import { fetchPayment } from '../api/payments'

export function ActivityDetailScreen() {
  const { paymentId } = useParams()
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  const { data: payment, isLoading, isError } = useQuery({
    queryKey: ['payments', 'detail', paymentId],
    queryFn: () => fetchPayment(accessToken!, paymentId!),
    enabled: Boolean(accessToken) && Boolean(paymentId),
    retry: false,
  })

  return (
    <div className="flex flex-col gap-4 px-5 pt-8">
      <h1 className="text-xl font-semibold text-foreground">Payment</h1>

      {isLoading && <p className="text-sm text-subtle">Loading…</p>}
      {isError && (
        <p className="text-sm text-subtle">Could not load this payment yet.</p>
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
