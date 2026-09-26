import { Link } from 'react-router-dom'
import { formatMoney } from '../api/format'
import { isOptimisticPaymentId } from '../hooks/useCreatePayment'
import { usePaymentsFeed } from '../hooks/usePaymentsFeed'

function statusColor(status: string): string {
  switch (status) {
    case 'COMPLETED':
      return 'text-positive'
    case 'FAILED':
      return 'text-negative'
    case 'CANCELLED':
      return 'text-muted'
    default:
      return 'text-pending'
  }
}

export function ActivityScreen() {
  const { data: payments, isLoading, isError } = usePaymentsFeed()

  return (
    <div className="flex flex-col gap-4 px-5 pb-6 pt-8">
      <h1 className="text-xl font-semibold text-foreground">Activity</h1>

      {isLoading ? (
        <p className="text-sm text-subtle">Loading…</p>
      ) : isError && (payments?.length ?? 0) === 0 ? (
        <p className="text-sm text-subtle">
          Nothing to show yet - transfers you send this session will appear here.
        </p>
      ) : payments && payments.length > 0 ? (
        <div className="flex flex-col gap-2">
          {payments.map((payment) => (
            <Link
              key={payment.paymentId}
              to={isOptimisticPaymentId(payment.paymentId) ? '#' : `/activity/${payment.paymentId}`}
              className="animate-enter flex items-center justify-between rounded-card bg-surface p-4 text-sm"
            >
              <div>
                <p className="text-foreground">{payment.reference || 'Transfer'}</p>
                <p className={`text-xs font-medium ${statusColor(payment.status)}`}>
                  {payment.status}
                </p>
              </div>
              <span className="text-foreground">
                {formatMoney(payment.amountMinor, payment.currency)}
              </span>
            </Link>
          ))}
        </div>
      ) : (
        <p className="text-sm text-subtle">No activity yet.</p>
      )}
    </div>
  )
}
