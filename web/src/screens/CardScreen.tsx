import { useState } from 'react'
import { formatMoney, parseMoneyToMinor } from '../api/format'
import { ApiError } from '../api/http'
import type { CardAuthorizationResponse, CardLimitResponse, CardResponse } from '../api/cards'
import { useAccountsWithBalances } from '../hooks/useAccountsWithBalances'
import { isPending, useCardAuthorizations } from '../hooks/useCardAuthorizations'
import { useFreezeCard, useUnfreezeCard } from '../hooks/useCardFreeze'
import { useCardLimits, useRemoveCardLimit, useUpsertCardLimit } from '../hooks/useCardLimits'
import { useCardReveal } from '../hooks/useCardReveal'
import { useCards, useIssueCard } from '../hooks/useCards'

const LIMIT_PERIODS: { period: CardLimitResponse['period']; label: string }[] = [
  { period: 'PER_TRANSACTION', label: 'Per transaction' },
  { period: 'DAILY', label: 'Daily' },
  { period: 'MONTHLY', label: 'Monthly' },
]

function CardFace({ card }: { card: CardResponse }) {
  const frozen = card.state === 'FROZEN'
  return (
    <div
      className={`relative flex aspect-[1.586] flex-col justify-between rounded-card bg-gradient-to-br from-accent to-accent-hover p-5 text-white shadow-lg transition-all duration-300 ${
        frozen ? 'grayscale' : ''
      }`}
    >
      {frozen && (
        <span className="absolute right-4 top-4 rounded-control bg-black/40 px-2 py-1 text-xs font-semibold">
          Frozen
        </span>
      )}
      <span className="text-sm font-medium opacity-80">{card.label || card.type}</span>
      <div>
        <p className="text-lg tracking-[0.3em]">•••• {card.panLast4}</p>
        <p className="mt-1 text-xs opacity-80">
          {String(card.expiryMonth).padStart(2, '0')}/{String(card.expiryYear).slice(-2)}
        </p>
      </div>
    </div>
  )
}

function RevealModal({ card, onClose }: { card: CardResponse; onClose: () => void }) {
  const [pin, setPin] = useState('')
  const { reveal, revealed, isRevealing, error, secondsRemaining, reset } = useCardReveal()
  const apiError = error instanceof ApiError ? error : null

  function handleClose() {
    reset()
    onClose()
  }

  function handleSubmit() {
    if (pin.length >= 4) {
      reveal(card.id, pin)
    }
  }

  function copy(value: string) {
    void navigator.clipboard.writeText(value)
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center px-5">
      <button type="button" aria-label="Close" className="absolute inset-0 bg-black/60" onClick={handleClose} />
      <div className="relative flex w-full max-w-xs flex-col gap-4 rounded-card bg-surface p-6">
        {!revealed ? (
          <>
            <h2 className="text-sm font-semibold text-foreground">Confirm it's you</h2>
            <p className="text-xs text-muted">Enter your card PIN to reveal the full number.</p>
            <input
              inputMode="numeric"
              type="password"
              maxLength={6}
              value={pin}
              onChange={(event) => setPin(event.target.value.replace(/\D/g, ''))}
              className="rounded-control border border-border bg-canvas px-3 py-2 text-center text-lg tracking-[0.4em] text-foreground"
              autoFocus
            />
            {apiError && <p className="text-xs text-negative">{apiError.detail ?? apiError.message}</p>}
            <div className="flex gap-3">
              <button
                type="button"
                onClick={handleClose}
                className="flex-1 rounded-control border border-border px-4 py-2 text-sm font-medium text-muted"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={pin.length < 4 || isRevealing}
                onClick={handleSubmit}
                className="flex-1 rounded-control bg-accent px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover disabled:opacity-40"
              >
                {isRevealing ? 'Checking…' : 'Reveal'}
              </button>
            </div>
          </>
        ) : (
          <>
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-semibold text-foreground">Card details</h2>
              <span className="text-xs text-subtle">hides in {secondsRemaining}s</span>
            </div>
            <button
              type="button"
              onClick={() => copy(revealed.pan)}
              className="flex items-center justify-between rounded-control bg-surface-raised px-3 py-2 text-left"
            >
              <span className="font-mono text-lg tracking-widest text-foreground">{revealed.pan}</span>
              <span className="text-xs text-muted">Copy</span>
            </button>
            <div className="flex gap-3">
              <button
                type="button"
                onClick={() => copy(revealed.cvv)}
                className="flex flex-1 items-center justify-between rounded-control bg-surface-raised px-3 py-2 text-left"
              >
                <span className="font-mono text-foreground">CVV {revealed.cvv}</span>
                <span className="text-xs text-muted">Copy</span>
              </button>
              <span className="flex flex-1 items-center rounded-control bg-surface-raised px-3 py-2 font-mono text-foreground">
                {String(revealed.expiryMonth).padStart(2, '0')}/{String(revealed.expiryYear).slice(-2)}
              </span>
            </div>
            <button
              type="button"
              onClick={handleClose}
              className="rounded-control border border-border px-4 py-2 text-sm font-medium text-muted"
            >
              Hide now
            </button>
          </>
        )}
      </div>
    </div>
  )
}

function LimitsEditor({ cardId, currency }: { cardId: string; currency: string }) {
  const { data: limits } = useCardLimits(cardId)
  const upsert = useUpsertCardLimit(cardId)
  const remove = useRemoveCardLimit(cardId)
  const [drafts, setDrafts] = useState<Record<string, string>>({})

  return (
    <section className="flex flex-col gap-3 rounded-card bg-surface p-5">
      <h2 className="text-sm font-medium text-muted">Limits</h2>
      {LIMIT_PERIODS.map(({ period, label }) => {
        const existing = limits?.find((limit) => limit.period === period) ?? null
        const draft = drafts[period] ?? ''
        // A limit is always in the wallet's own currency (never asked for - card-service
        // resolves it server-side), so that is what a typed amount here is parsed as too.
        const amountMinor = parseMoneyToMinor(draft, currency)
        return (
          <div key={period} className="flex items-center gap-2">
            <span className="w-28 shrink-0 text-xs text-muted">{label}</span>
            <input
              inputMode="decimal"
              placeholder={existing ? formatMoney(existing.amountMinor, existing.currency) : 'No limit'}
              value={draft}
              onChange={(event) => setDrafts((old) => ({ ...old, [period]: event.target.value }))}
              className="flex-1 rounded-control border border-border bg-canvas px-2 py-1.5 text-sm text-foreground"
            />
            <button
              type="button"
              disabled={amountMinor === null || upsert.isPending}
              onClick={() => {
                if (amountMinor === null) return
                upsert.mutate(
                  { period, amountMinor },
                  { onSuccess: () => setDrafts((old) => ({ ...old, [period]: '' })) },
                )
              }}
              className="rounded-control bg-accent px-3 py-1.5 text-xs font-medium text-white disabled:opacity-30"
            >
              Set
            </button>
            {existing && (
              <button
                type="button"
                onClick={() => remove.mutate(period)}
                className="rounded-control border border-border px-2 py-1.5 text-xs text-muted"
              >
                Remove
              </button>
            )}
          </div>
        )
      })}
    </section>
  )
}

function statusLabel(authorization: CardAuthorizationResponse): string {
  switch (authorization.state) {
    case 'APPROVED':
      return 'Pending'
    case 'SETTLED':
    case 'PARTIALLY_SETTLED':
      return 'Settled'
    case 'DECLINED':
      return `Declined${authorization.declineReason ? ` · ${authorization.declineReason}` : ''}`
    case 'REVERSED':
      return 'Reversed'
    case 'EXPIRED':
      return 'Expired'
  }
}

function statusColor(authorization: CardAuthorizationResponse): string {
  if (isPending(authorization)) return 'text-pending'
  if (authorization.state === 'DECLINED') return 'text-negative'
  if (authorization.state === 'SETTLED' || authorization.state === 'PARTIALLY_SETTLED') return 'text-positive'
  return 'text-muted'
}

function SpendFeedRow({ authorization }: { authorization: CardAuthorizationResponse }) {
  const pending = isPending(authorization)
  const displayMinor = authorization.settledMinor ?? authorization.billingMinor ?? authorization.amountMinor
  const displayCurrency = authorization.billingCurrency ?? authorization.currency
  const isForeign = authorization.billingCurrency !== null && authorization.billingCurrency !== authorization.currency

  return (
    <div
      className={`animate-enter flex items-center justify-between rounded-card bg-surface p-4 text-sm transition-colors ${
        pending ? 'border border-dashed border-border' : 'border border-transparent'
      }`}
    >
      <div className="flex items-center gap-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-surface-raised text-xs font-semibold text-muted">
          {authorization.merchantName.charAt(0).toUpperCase()}
        </span>
        <div>
          <p className="text-foreground">{authorization.merchantName}</p>
          <p className={`text-xs font-medium ${statusColor(authorization)}`}>{statusLabel(authorization)}</p>
        </div>
      </div>
      <div className="flex flex-col items-end">
        <span className="text-foreground">{formatMoney(displayMinor, displayCurrency)}</span>
        {isForeign && (
          <span className="text-xs text-subtle">{formatMoney(authorization.amountMinor, authorization.currency)}</span>
        )}
      </div>
    </div>
  )
}

function SpendFeed({ cardId }: { cardId: string }) {
  const { data: authorizations, isLoading } = useCardAuthorizations(cardId)

  return (
    <section className="flex flex-col gap-2">
      <h2 className="text-sm font-medium text-muted">Spend feed</h2>
      {isLoading ? (
        <p className="text-sm text-subtle">Loading…</p>
      ) : authorizations && authorizations.length > 0 ? (
        <div className="flex flex-col gap-2">
          {authorizations.map((authorization) => (
            <SpendFeedRow key={authorization.networkRef} authorization={authorization} />
          ))}
        </div>
      ) : (
        <p className="rounded-card bg-surface p-6 text-sm text-subtle">No spend on this card yet.</p>
      )}
    </section>
  )
}

function CardDetail({ card }: { card: CardResponse }) {
  const [revealing, setRevealing] = useState(false)
  const freeze = useFreezeCard()
  const unfreeze = useUnfreezeCard()
  const toggling = freeze.isPending || unfreeze.isPending
  const { accounts } = useAccountsWithBalances()
  // Falls back to EUR only if the funding-account projection hasn't loaded yet - the limits
  // endpoint itself always resolves the real currency server-side regardless of this guess.
  const currency = accounts.find((account) => account.id === card.accountId)?.currency ?? 'EUR'

  return (
    <div className="flex flex-col gap-5">
      <CardFace card={card} />

      <div className="flex gap-3">
        <button
          type="button"
          onClick={() => setRevealing(true)}
          className="flex-1 rounded-control border border-border px-4 py-2 text-sm font-medium text-muted transition-colors hover:text-foreground"
        >
          Reveal
        </button>
        <button
          type="button"
          disabled={toggling || card.state === 'CANCELLED'}
          onClick={() => (card.state === 'FROZEN' ? unfreeze.mutate(card.id) : freeze.mutate(card.id))}
          className="flex-1 rounded-control bg-accent px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover disabled:opacity-40"
        >
          {card.state === 'FROZEN' ? 'Unfreeze' : 'Freeze'}
        </button>
      </div>

      <LimitsEditor cardId={card.id} currency={currency} />
      <SpendFeed cardId={card.id} />

      {revealing && <RevealModal card={card} onClose={() => setRevealing(false)} />}
    </div>
  )
}

function IssueCardForm() {
  const { accounts } = useAccountsWithBalances()
  const activeAccounts = accounts.filter((account) => account.state === 'ACTIVE')
  const [accountId, setAccountId] = useState(activeAccounts[0]?.id ?? '')
  const [pin, setPin] = useState('')
  const issueCard = useIssueCard()
  const apiError = issueCard.error instanceof ApiError ? issueCard.error : null

  // Accounts load after this component's first render (accountId's useState initializer sees
  // an empty list), so the default selection is adjusted here once they arrive - React's
  // documented "adjust state during render" pattern, not an effect, since it settles in the
  // same render pass rather than triggering an extra one.
  if (activeAccounts.length > 0 && !accountId) {
    setAccountId(activeAccounts[0].id)
  }

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted">You don't have a card yet. Issue a virtual one to start spending.</p>
      <section className="flex flex-col gap-3 rounded-card bg-surface p-5">
        <label className="flex flex-col gap-1 text-xs text-muted">
          Funding wallet
          <select
            value={accountId}
            onChange={(event) => setAccountId(event.target.value)}
            className="rounded-control border border-border bg-canvas px-3 py-2 text-sm text-foreground"
          >
            {activeAccounts.map((account) => (
              <option key={account.id} value={account.id}>
                {account.currency} · {account.name}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-xs text-muted">
          Choose a PIN (4-6 digits)
          <input
            inputMode="numeric"
            type="password"
            maxLength={6}
            value={pin}
            onChange={(event) => setPin(event.target.value.replace(/\D/g, ''))}
            className="rounded-control border border-border bg-canvas px-3 py-2 text-lg tracking-[0.4em] text-foreground"
          />
        </label>
        {apiError && <p className="text-xs text-negative">{apiError.detail ?? apiError.message}</p>}
        <button
          type="button"
          disabled={!accountId || pin.length < 4 || issueCard.isPending}
          onClick={() => issueCard.mutate({ accountId, type: 'VIRTUAL', label: 'My card', pin })}
          className="rounded-control bg-accent px-4 py-3 text-sm font-medium text-white transition-colors hover:bg-accent-hover disabled:opacity-40"
        >
          {issueCard.isPending ? 'Issuing…' : 'Issue card'}
        </button>
      </section>
    </div>
  )
}

export function CardScreen() {
  const { data: cards, isLoading, isError } = useCards()
  const card = cards?.[0]

  return (
    <div className="flex flex-col gap-5 px-5 pb-6 pt-8">
      <h1 className="text-xl font-semibold text-foreground">Card</h1>

      {isLoading ? (
        <p className="text-sm text-subtle">Loading…</p>
      ) : isError ? (
        <p className="text-sm text-negative">Could not load your cards.</p>
      ) : card ? (
        <CardDetail card={card} />
      ) : (
        <IssueCardForm />
      )}
    </div>
  )
}
