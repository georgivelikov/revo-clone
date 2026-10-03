import { Link } from 'react-router-dom'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { RowSkeletonList } from '../components/Skeleton'
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
  const { data: payments, isLoading, isError, refetch } = usePaymentsFeed()

  return (
    <div className="flex flex-col gap-4 px-5 pb-6 pt-8">
      <h1 className="text-xl font-semibold text-foreground">Activity</h1>

      {isLoading ? (
        <RowSkeletonList count={4} />
      ) : isError && (payments?.length ?? 0) === 0 ? (
        <ErrorState title="Could not load your activity." onRetry={() => void refetch()} />
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
        <EmptyState
          icon="≣"
          title="No activity yet"
          description="Transfers you send or receive will appear here."
        />
      )}
    </div>
  )
}
