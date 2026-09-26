import { useAuth } from 'react-oidc-context'
import { Link } from 'react-router-dom'
import { formatMoney } from '../api/format'
import { useAccountsWithBalances } from '../hooks/useAccountsWithBalances'
import { usePaymentsFeed } from '../hooks/usePaymentsFeed'
import { isOptimisticPaymentId } from '../hooks/useCreatePayment'

export function HomeScreen() {
  const auth = useAuth()
  const { accounts, isLoading, isError } = useAccountsWithBalances()
  const paymentsFeed = usePaymentsFeed()
  const recentPayments = (paymentsFeed.data ?? []).slice(0, 3)

  const primary = accounts[0]

  return (
    <div className="flex flex-col gap-6 px-5 pb-6 pt-8">
      <header className="flex items-center justify-between">
        <div>
          <p className="text-sm text-muted">Welcome back</p>
          <h1 className="text-xl font-semibold text-foreground">
            {auth.user?.profile.name ?? auth.user?.profile.email ?? 'there'}
          </h1>
        </div>
        <button
          type="button"
          onClick={() => void auth.signoutRedirect()}
          className="rounded-control border border-border px-3 py-1.5 text-xs font-medium text-muted transition-colors hover:text-foreground"
        >
          Log out
        </button>
      </header>

      <section className="rounded-card bg-surface p-6">
        <p className="text-sm text-muted">Total balance</p>
        {isLoading ? (
          <p className="mt-1 text-3xl font-semibold text-foreground">– . –</p>
        ) : isError ? (
          <p className="mt-1 text-lg font-medium text-negative">Could not load your accounts.</p>
        ) : primary ? (
          <p className="mt-1 text-3xl font-semibold text-foreground">
            {primary.availableMinor === null
              ? '– . –'
              : formatMoney(primary.availableMinor, primary.currency)}
          </p>
        ) : (
          <p className="mt-1 text-lg font-medium text-muted">No accounts yet.</p>
        )}

        {accounts.length > 1 && (
          <div className="mt-4 flex flex-wrap gap-2">
            {accounts.map((account) => (
              <span
                key={account.id}
                className="rounded-control bg-surface-raised px-3 py-1.5 text-xs font-medium text-muted"
              >
                {account.currency}
                {' · '}
                {account.balanceError
                  ? 'unavailable'
                  : account.availableMinor === null
                    ? '…'
                    : formatMoney(account.availableMinor, account.currency)}
              </span>
            ))}
          </div>
        )}
      </section>

      <section>
        <h2 className="text-sm font-medium text-muted">Recent activity</h2>
        {recentPayments.length === 0 ? (
          <div className="mt-3 rounded-card bg-surface p-6 text-sm text-subtle">
            No transfers yet.
          </div>
        ) : (
          <div className="mt-3 flex flex-col gap-2">
            {recentPayments.map((payment) => (
              <Link
                key={payment.paymentId}
                to={isOptimisticPaymentId(payment.paymentId) ? '#' : `/activity/${payment.paymentId}`}
                className="animate-enter flex items-center justify-between rounded-card bg-surface p-4 text-sm"
              >
                <span className="text-foreground">{payment.reference || 'Transfer'}</span>
                <span className="flex flex-col items-end">
                  <span className="text-foreground">
                    {formatMoney(payment.amountMinor, payment.currency)}
                  </span>
                  <span className="text-xs text-muted">{payment.status}</span>
                </span>
              </Link>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}
