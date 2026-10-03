import type { FormEvent } from 'react'
import { useRef, useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { Link } from 'react-router-dom'
import { formatMoney, parseMoneyToMinor } from '../api/format'
import { ApiError } from '../api/http'
import type { CreatePaymentRequest } from '../api/payments'
import { useCreatePayment } from '../hooks/useCreatePayment'
import { useAccountsWithBalances } from '../hooks/useAccountsWithBalances'
import type { RecentRecipient } from '../lib/recentRecipients'
import { loadRecentRecipients, rememberRecipient } from '../lib/recentRecipients'

type Step = 'recipient' | 'amount' | 'review' | 'result'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function statusStyles(status: string): string {
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

export function TransferScreen() {
  const auth = useAuth()
  const userId = auth.user?.profile.sub
  const { accounts } = useAccountsWithBalances()
  // Vaults come back from the same accounts call (see HomeScreen's note) but can't send or
  // receive a transfer directly - only wallets belong in this picker.
  const activeAccounts = accounts.filter(
    (account) => account.state === 'ACTIVE' && account.type === 'WALLET',
  )
  const mutation = useCreatePayment()

  const [step, setStep] = useState<Step>('recipient')
  const [sourceAccountId, setSourceAccountId] = useState<string | undefined>(undefined)
  const [targetAccountId, setTargetAccountId] = useState('')
  const [targetAccountIdInput, setTargetAccountIdInput] = useState('')
  const [recipientLabel, setRecipientLabel] = useState('')
  const [recipientError, setRecipientError] = useState<string | null>(null)
  const [amountInput, setAmountInput] = useState('')
  const [reference, setReference] = useState('')

  const idempotencyKeyRef = useRef<string | null>(null)
  const lastRequestRef = useRef<CreatePaymentRequest | null>(null)

  const sourceAccount =
    activeAccounts.find((account) => account.id === sourceAccountId) ?? activeAccounts[0]
  const amountMinor = sourceAccount ? parseMoneyToMinor(amountInput, sourceAccount.currency) : null
  const insufficientBalance =
    sourceAccount?.availableMinor != null && amountMinor != null
      ? amountMinor > sourceAccount.availableMinor
      : false

  const recentRecipients = userId ? loadRecentRecipients(userId) : []

  function resetWizard() {
    setStep('recipient')
    setTargetAccountId('')
    setTargetAccountIdInput('')
    setRecipientLabel('')
    setRecipientError(null)
    setAmountInput('')
    setReference('')
    idempotencyKeyRef.current = null
    lastRequestRef.current = null
    mutation.reset()
  }

  function selectRecipient(recipient: RecentRecipient) {
    setTargetAccountId(recipient.accountId)
    setRecipientLabel(recipient.label)
    setRecipientError(null)
    setStep('amount')
  }

  function handleRecipientSubmit(event: FormEvent) {
    event.preventDefault()
    const candidate = targetAccountIdInput.trim()
    if (!UUID_PATTERN.test(candidate)) {
      setRecipientError('Enter a valid account id.')
      return
    }
    if (sourceAccount && candidate === sourceAccount.id) {
      setRecipientError('You cannot send money to the account you are sending from.')
      return
    }
    setTargetAccountId(candidate)
    setRecipientLabel('')
    setRecipientError(null)
    setStep('amount')
  }

  function handleConfirm() {
    if (!sourceAccount || amountMinor === null) {
      return
    }
    if (!idempotencyKeyRef.current) {
      // Client-generated and kept for retries - a server-generated key would defeat the whole
      // point of idempotency, since a retry needs to prove it is the *same* attempt.
      idempotencyKeyRef.current = crypto.randomUUID()
    }
    const request: CreatePaymentRequest = {
      sourceAccountId: sourceAccount.id,
      targetAccountId,
      amountMinor,
      currency: sourceAccount.currency,
      reference: reference.trim() === '' ? null : reference.trim(),
    }
    lastRequestRef.current = request
    setStep('result')
    mutation.mutate(
      { idempotencyKey: idempotencyKeyRef.current, request },
      {
        onSuccess: () => {
          if (userId) {
            rememberRecipient(userId, {
              accountId: targetAccountId,
              currency: sourceAccount.currency,
              label: recipientLabel || targetAccountId,
              lastUsedAt: new Date().toISOString(),
            })
          }
        },
      },
    )
  }

  function retrySameRequest() {
    if (!idempotencyKeyRef.current || !lastRequestRef.current) {
      return
    }
    mutation.mutate({ idempotencyKey: idempotencyKeyRef.current, request: lastRequestRef.current })
  }

  function editAndRetry() {
    // A different request needs a different key - reusing one here would make the ledger's
    // key derivation (`payment:<paymentId>`) irrelevant, since this would still be a brand new
    // transfer as far as the user is concerned, not a retry of the failed one.
    idempotencyKeyRef.current = null
    mutation.reset()
    setStep('review')
  }

  const error = mutation.error instanceof ApiError ? mutation.error : null
  const isInFlightDuplicate = error !== null && error.status === 409

  return (
    <div className="flex flex-col gap-5 px-5 pb-6 pt-8">
      <h1 className="text-xl font-semibold text-foreground">Transfer</h1>

      {step === 'recipient' && (
        <div className="flex flex-col gap-5">
          {activeAccounts.length > 1 && (
            <section className="flex flex-col gap-2">
              <h2 className="text-sm font-medium text-muted">From</h2>
              <div className="flex flex-wrap gap-2">
                {activeAccounts.map((account) => (
                  <button
                    key={account.id}
                    type="button"
                    onClick={() => setSourceAccountId(account.id)}
                    className={`rounded-control px-3 py-1.5 text-xs font-medium transition-colors ${
                      account.id === sourceAccount?.id
                        ? 'bg-accent text-white'
                        : 'bg-surface-raised text-muted'
                    }`}
                  >
                    {account.name} · {account.currency}
                  </button>
                ))}
              </div>
            </section>
          )}

          {recentRecipients.length > 0 && (
            <section className="flex flex-col gap-2">
              <h2 className="text-sm font-medium text-muted">Recent</h2>
              <div className="flex flex-col gap-2">
                {recentRecipients
                  .filter((recipient) => !sourceAccount || recipient.currency === sourceAccount.currency)
                  .map((recipient) => (
                    <button
                      key={recipient.accountId}
                      type="button"
                      onClick={() => selectRecipient(recipient)}
                      className="rounded-card bg-surface p-4 text-left text-sm text-foreground transition-colors hover:bg-surface-raised"
                    >
                      <p className="font-medium">{recipient.label}</p>
                      <p className="text-xs text-subtle">{recipient.accountId}</p>
                    </button>
                  ))}
              </div>
            </section>
          )}

          <section className="flex flex-col gap-3 rounded-card bg-surface p-5">
            <h2 className="text-sm font-medium text-muted">Send to a new recipient</h2>
            <form onSubmit={handleRecipientSubmit} className="flex flex-col gap-3">
              <label className="flex flex-col gap-1 text-xs text-muted">
                Recipient account id
                <input
                  value={targetAccountIdInput}
                  onChange={(event) => setTargetAccountIdInput(event.target.value)}
                  placeholder="00000000-0000-0000-0000-000000000000"
                  className="rounded-control border border-border bg-canvas px-3 py-2 text-sm text-foreground"
                />
              </label>
              {recipientError && <p className="text-xs text-negative">{recipientError}</p>}
              <button
                type="submit"
                className="rounded-control bg-accent px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover"
              >
                Continue
              </button>
            </form>
          </section>
        </div>
      )}

      {step === 'amount' && sourceAccount && (
        <div className="flex flex-col gap-5">
          <p className="text-sm text-muted">
            To <span className="text-foreground">{recipientLabel || targetAccountId}</span>
          </p>

          <section className="flex flex-col gap-3 rounded-card bg-surface p-5">
            <label className="flex flex-col gap-1 text-xs text-muted">
              Amount ({sourceAccount.currency})
              <input
                inputMode="decimal"
                value={amountInput}
                onChange={(event) => setAmountInput(event.target.value)}
                placeholder="0.00"
                className="rounded-control border border-border bg-canvas px-3 py-2 text-lg text-foreground"
                autoFocus
              />
            </label>
            {sourceAccount.availableMinor !== null && (
              <p className="text-xs text-subtle">
                Available: {formatMoney(sourceAccount.availableMinor, sourceAccount.currency)}
              </p>
            )}
            {insufficientBalance && (
              <p className="text-xs text-pending">
                This is more than your available balance - the transfer may still be accepted and
                fail once the ledger checks it.
              </p>
            )}
            <label className="flex flex-col gap-1 text-xs text-muted">
              Note (optional)
              <input
                value={reference}
                onChange={(event) => setReference(event.target.value.slice(0, 140))}
                placeholder="What's it for?"
                className="rounded-control border border-border bg-canvas px-3 py-2 text-sm text-foreground"
              />
            </label>
          </section>

          <div className="flex gap-3">
            <button
              type="button"
              onClick={() => setStep('recipient')}
              className="flex-1 rounded-control border border-border px-4 py-2 text-sm font-medium text-muted"
            >
              Back
            </button>
            <button
              type="button"
              disabled={amountMinor === null}
              onClick={() => setStep('review')}
              className="flex-1 rounded-control bg-accent px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover disabled:opacity-40"
            >
              Review
            </button>
          </div>
        </div>
      )}

      {step === 'review' && sourceAccount && amountMinor !== null && (
        <div className="flex flex-col gap-5">
          <section className="flex flex-col gap-3 rounded-card bg-surface p-5 text-sm">
            <div className="flex justify-between">
              <span className="text-muted">From</span>
              <span className="text-foreground">
                {sourceAccount.name} · {sourceAccount.currency}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-muted">To</span>
              <span className="text-foreground">{recipientLabel || targetAccountId}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-muted">Amount</span>
              <span className="text-lg font-semibold text-foreground">
                {formatMoney(amountMinor, sourceAccount.currency)}
              </span>
            </div>
            {reference && (
              <div className="flex justify-between">
                <span className="text-muted">Note</span>
                <span className="text-foreground">{reference}</span>
              </div>
            )}
          </section>

          <div className="flex gap-3">
            <button
              type="button"
              onClick={() => setStep('amount')}
              className="flex-1 rounded-control border border-border px-4 py-2 text-sm font-medium text-muted"
            >
              Back
            </button>
            <button
              type="button"
              onClick={handleConfirm}
              className="flex-1 rounded-control bg-accent px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover"
            >
              Confirm & send
            </button>
          </div>
        </div>
      )}

      {step === 'result' && (
        <div className="animate-enter flex flex-col gap-5 rounded-card bg-surface p-6 text-center">
          {mutation.isPending && (
            <>
              <p className={`text-lg font-semibold ${statusStyles('PENDING')}`}>Sending…</p>
              <p className="text-sm text-subtle">Waiting for the payment to be accepted.</p>
            </>
          )}

          {mutation.isError && isInFlightDuplicate && (
            <>
              <p className={`text-lg font-semibold ${statusStyles('PENDING')}`}>Still processing</p>
              <p className="text-sm text-subtle">
                A previous attempt with this transfer is already being handled. Check Activity for
                its result.
              </p>
            </>
          )}

          {mutation.isError && !isInFlightDuplicate && (
            <>
              <p className="text-lg font-semibold text-negative">Could not send</p>
              <p className="text-sm text-subtle">{error?.detail ?? mutation.error?.message}</p>
              <div className="flex gap-3">
                <button
                  type="button"
                  onClick={editAndRetry}
                  className="flex-1 rounded-control border border-border px-4 py-2 text-sm font-medium text-muted"
                >
                  Edit transfer
                </button>
                <button
                  type="button"
                  onClick={retrySameRequest}
                  className="flex-1 rounded-control bg-accent px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover"
                >
                  Try again
                </button>
              </div>
            </>
          )}

          {mutation.isSuccess && (
            <>
              <p className={`text-lg font-semibold ${statusStyles(mutation.data.status)}`}>
                {mutation.data.status === 'PENDING' ? 'Sent - pending' : mutation.data.status}
              </p>
              <p className="text-2xl font-semibold text-foreground">
                {formatMoney(mutation.data.amountMinor, mutation.data.currency)}
              </p>
              {mutation.data.failureReason && (
                <p className="text-sm text-negative">{mutation.data.failureReason}</p>
              )}
              <Link
                to={`/activity/${mutation.data.paymentId}`}
                className="text-sm font-medium text-accent"
              >
                View in Activity
              </Link>
              <button
                type="button"
                onClick={resetWizard}
                className="rounded-control border border-border px-4 py-2 text-sm font-medium text-muted"
              >
                New transfer
              </button>
            </>
          )}
        </div>
      )}
    </div>
  )
}
