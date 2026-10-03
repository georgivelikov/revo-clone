import { useAuth } from 'react-oidc-context'
import { Link } from 'react-router-dom'
import { BalanceSkeleton, RowSkeletonList } from '../components/Skeleton'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { CountUpMoney } from '../components/CountUpMoney'
import { formatMoney } from '../api/format'
import { useAccountsWithBalances } from '../hooks/useAccountsWithBalances'
import { usePaymentsFeed } from '../hooks/usePaymentsFeed'
import { isOptimisticPaymentId } from '../hooks/useCreatePayment'

export function HomeScreen() {
  const auth = useAuth()
  const { accounts: allAccounts, isLoading, isError, refetch } = useAccountsWithBalances()
  // GET /api/accounts/me returns every account this user owns, vaults included (a vault is its
  // own backed `account` row - see account-service's domain). "Total balance" and the currency
  // chips below mean spendable wallets, not a vault's own balance - found live testing task
  // 09.6's polish pass: without this filter, a round-up vault showed up as a second, always-€0
  // chip for whichever currency it happened to be denominated in.
  const accounts = allAccounts.filter((account) => account.type === 'WALLET')
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
          <div className="mt-2">
            <BalanceSkeleton />
          </div>
        ) : isError ? (
          <div className="mt-2">
            <ErrorState title="Could not load your accounts." onRetry={() => void refetch()} />
          </div>
        ) : primary ? (
          primary.availableMinor === null ? (
            <p className="mt-1 text-3xl font-semibold text-foreground">
              {primary.balanceError ? 'Balance unavailable' : '…'}
            </p>
          ) : (
            <CountUpMoney
              amountMinor={primary.availableMinor}
              currency={primary.currency}
              className="mt-1 block text-3xl font-semibold text-foreground"
            />
          )
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
        {paymentsFeed.isLoading ? (
          <div className="mt-3">
            <RowSkeletonList count={2} />
          </div>
        ) : recentPayments.length === 0 ? (
          <div className="mt-3">
            <EmptyState
              icon="≣"
              title="No transfers yet"
              description="Send money from the Transfer tab and it will show up here."
            />
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
