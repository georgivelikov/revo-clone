import { useEffect, useRef, useState } from 'react'
import { formatMoney, parseMoneyToMinor } from '../api/format'
import { ApiError } from '../api/http'
import type { QuoteResponse } from '../api/fx'
import { useAccountsWithBalances } from '../hooks/useAccountsWithBalances'
import { useCreateQuote } from '../hooks/useCreateQuote'
import { useExecuteQuote } from '../hooks/useExecuteQuote'
import { useRateTick } from '../hooks/useRateTicks'
import { canonicalPair, currencyFlag, invertForDisplay, isInvertedOrder } from '../lib/currencyPair'

type Step = 'setup' | 'quoted' | 'result'

const QUOTE_TTL_MS = 30_000
const RING_RADIUS = 18
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS

function feePercent(spreadBps: number): string {
  return (spreadBps / 100).toFixed(2)
}

/** A simple SVG countdown ring - a quarter-turn per 7.5s of a 30s quote, ticking down to empty. */
function CountdownRing({ fractionRemaining }: { fractionRemaining: number }) {
  const offset = RING_CIRCUMFERENCE * (1 - Math.max(0, Math.min(1, fractionRemaining)))
  const urgent = fractionRemaining < 1 / 3
  return (
    <svg viewBox="0 0 44 44" className="h-11 w-11 -rotate-90">
      <circle cx="22" cy="22" r={RING_RADIUS} fill="none" strokeWidth="4" className="stroke-border" />
      <circle
        cx="22"
        cy="22"
        r={RING_RADIUS}
        fill="none"
        strokeWidth="4"
        strokeLinecap="round"
        strokeDasharray={RING_CIRCUMFERENCE}
        strokeDashoffset={offset}
        className={urgent ? 'stroke-negative transition-[stroke-dashoffset]' : 'stroke-accent transition-[stroke-dashoffset]'}
      />
    </svg>
  )
}

export function ExchangeScreen() {
  const { accounts } = useAccountsWithBalances()
  const activeAccounts = accounts.filter((account) => account.state === 'ACTIVE')
  const currencies = [...new Set(activeAccounts.map((account) => account.currency))]

  const [step, setStep] = useState<Step>('setup')
  const [sellCurrency, setSellCurrency] = useState(currencies[0] ?? '')
  const [buyCurrency, setBuyCurrency] = useState(
    currencies.find((currency) => currency !== currencies[0]) ?? currencies[0] ?? '',
  )
  const [amountInput, setAmountInput] = useState('')
  const [quote, setQuote] = useState<QuoteResponse | null>(null)
  const [nowMs, setNowMs] = useState(() => Date.now())

  const executeIdempotencyKeyRef = useRef<string | null>(null)

  const sellAccount = activeAccounts.find((account) => account.currency === sellCurrency)
  const sellMinor = sellAccount ? parseMoneyToMinor(amountInput, sellCurrency) : null

  const pair = sellCurrency && buyCurrency ? canonicalPair(sellCurrency, buyCurrency) : ''
  const tick = useRateTick(pair)
  const liveMid =
    tick && isInvertedOrder(sellCurrency, buyCurrency) ? invertForDisplay(tick.mid) : (tick?.mid ?? null)

  // A subtle flash on change, per the phase's own description of a live-ticking rate - not a
  // value used for anything, purely a re-render trigger for the CSS below to key off.
  const [flashKey, setFlashKey] = useState(0)
  const lastMidRef = useRef<string | null>(null)
  useEffect(() => {
    if (liveMid !== null && liveMid !== lastMidRef.current) {
      lastMidRef.current = liveMid
      setFlashKey((key) => key + 1)
    }
  }, [liveMid])

  const createQuoteMutation = useCreateQuote()
  const executeQuoteMutation = useExecuteQuote()

  // Ticks the countdown ring and detects expiry without waiting on a click - "on expiry the
  // confirm button disables", not only when the user happens to try it.
  useEffect(() => {
    if (step !== 'quoted') {
      return
    }
    const interval = setInterval(() => setNowMs(Date.now()), 250)
    return () => clearInterval(interval)
  }, [step])

  const expiresAtMs = quote ? new Date(quote.expiresAt).getTime() : null
  const msRemaining = expiresAtMs !== null ? expiresAtMs - nowMs : null
  const isExpired = msRemaining !== null && msRemaining <= 0
  const fractionRemaining = msRemaining !== null ? msRemaining / QUOTE_TTL_MS : 1

  function swapCurrencies() {
    setSellCurrency(buyCurrency)
    setBuyCurrency(sellCurrency)
  }

  function handleGetQuote() {
    if (!sellCurrency || !buyCurrency || sellMinor === null) {
      return
    }
    createQuoteMutation.mutate(
      {
        idempotencyKey: crypto.randomUUID(),
        request: { sellCurrency, buyCurrency, sellMinor, buyMinor: null },
      },
      {
        onSuccess: (result) => {
          setQuote(result)
          setNowMs(Date.now())
          setStep('quoted')
        },
      },
    )
  }

  function handleRefreshQuote() {
    setQuote(null)
    setStep('setup')
    createQuoteMutation.reset()
  }

  function handleConfirm() {
    if (!quote || isExpired) {
      return
    }
    if (!executeIdempotencyKeyRef.current) {
      executeIdempotencyKeyRef.current = crypto.randomUUID()
    }
    setStep('result')
    executeQuoteMutation.mutate({ idempotencyKey: executeIdempotencyKeyRef.current, quoteId: quote.quoteId })
  }

  function resetAll() {
    setStep('setup')
    setQuote(null)
    setAmountInput('')
    executeIdempotencyKeyRef.current = null
    createQuoteMutation.reset()
    executeQuoteMutation.reset()
  }

  if (currencies.length < 2) {
    return (
      <div className="flex flex-col gap-3 px-5 pb-6 pt-8">
        <h1 className="text-xl font-semibold text-foreground">Exchange</h1>
        <p className="text-sm text-muted">
          You need wallets in at least two currencies to exchange money.
        </p>
      </div>
    )
  }

  const quoteError = createQuoteMutation.error instanceof ApiError ? createQuoteMutation.error : null
  const executeError = executeQuoteMutation.error instanceof ApiError ? executeQuoteMutation.error : null

  return (
    <div className="flex flex-col gap-5 px-5 pb-6 pt-8">
      <h1 className="text-xl font-semibold text-foreground">Exchange</h1>

      {step === 'setup' && sellAccount && (
        <div className="flex flex-col gap-5">
          <section className="flex flex-col gap-3 rounded-card bg-surface p-5">
            <div className="flex items-center justify-between">
              <span className="text-xs font-medium text-muted">You sell</span>
              <div className="flex gap-1">
                {currencies.map((currency) => (
                  <button
                    key={currency}
                    type="button"
                    onClick={() => setSellCurrency(currency)}
                    disabled={currency === buyCurrency}
                    className={`rounded-control px-2.5 py-1 text-xs font-medium transition-colors disabled:opacity-30 ${
                      currency === sellCurrency ? 'bg-accent text-white' : 'bg-surface-raised text-muted'
                    }`}
                  >
                    {currencyFlag(currency)} {currency}
                  </button>
                ))}
              </div>
            </div>
            <input
              inputMode="decimal"
              value={amountInput}
              onChange={(event) => setAmountInput(event.target.value)}
              placeholder="0.00"
              className="rounded-control border border-border bg-canvas px-3 py-2 text-lg text-foreground"
              autoFocus
            />
            {sellAccount.availableMinor !== null && (
              <p className="text-xs text-subtle">
                Available: {formatMoney(sellAccount.availableMinor, sellCurrency)}
              </p>
            )}
          </section>

          <div className="flex justify-center">
            <button
              type="button"
              onClick={swapCurrencies}
              aria-label="Swap currencies"
              className="rounded-full border border-border bg-surface p-2 text-lg text-muted transition-colors hover:text-foreground"
            >
              ⇅
            </button>
          </div>

          <section className="flex flex-col gap-3 rounded-card bg-surface p-5">
            <div className="flex items-center justify-between">
              <span className="text-xs font-medium text-muted">You get</span>
              <div className="flex gap-1">
                {currencies.map((currency) => (
                  <button
                    key={currency}
                    type="button"
                    onClick={() => setBuyCurrency(currency)}
                    disabled={currency === sellCurrency}
                    className={`rounded-control px-2.5 py-1 text-xs font-medium transition-colors disabled:opacity-30 ${
                      currency === buyCurrency ? 'bg-accent text-white' : 'bg-surface-raised text-muted'
                    }`}
                  >
                    {currencyFlag(currency)} {currency}
                  </button>
                ))}
              </div>
            </div>
            <p key={flashKey} className="animate-flash text-lg text-foreground">
              {liveMid ? `1 ${sellCurrency} ≈ ${liveMid} ${buyCurrency}` : 'Waiting for a live rate…'}
            </p>
          </section>

          {quoteError && <p className="text-xs text-negative">{quoteError.detail ?? quoteError.message}</p>}

          <button
            type="button"
            disabled={sellMinor === null || createQuoteMutation.isPending}
            onClick={handleGetQuote}
            className="rounded-control bg-accent px-4 py-3 text-sm font-medium text-white transition-colors hover:bg-accent-hover disabled:opacity-40"
          >
            {createQuoteMutation.isPending ? 'Getting quote…' : 'Get quote'}
          </button>
        </div>
      )}

      {step === 'quoted' && quote && (
        <div className="flex flex-col gap-5">
          <section className="flex flex-col items-center gap-4 rounded-card bg-surface p-6 text-center">
            <div className="relative flex h-11 w-11 items-center justify-center">
              <CountdownRing fractionRemaining={fractionRemaining} />
              <span className="absolute text-xs font-semibold text-foreground">
                {Math.max(0, Math.ceil((msRemaining ?? 0) / 1000))}
              </span>
            </div>

            <p className="text-2xl font-semibold text-foreground">
              {formatMoney(quote.buyMinor, quote.buyCurrency)}
            </p>
            <p className="text-sm text-muted">
              You get {formatMoney(quote.buyMinor, quote.buyCurrency)} · rate {quote.clientRate} · includes{' '}
              {feePercent(quote.spreadBps)}% fee
            </p>
            <p className="text-xs text-subtle">
              Selling {formatMoney(quote.sellMinor, quote.sellCurrency)}
            </p>

            {isExpired && <p className="text-sm font-medium text-negative">Rate expired</p>}
          </section>

          <div className="flex gap-3">
            <button
              type="button"
              onClick={handleRefreshQuote}
              className="flex-1 rounded-control border border-border px-4 py-2 text-sm font-medium text-muted"
            >
              {isExpired ? 'Get a new quote' : 'Cancel'}
            </button>
            <button
              type="button"
              disabled={isExpired}
              onClick={handleConfirm}
              className="flex-1 rounded-control bg-accent px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover disabled:opacity-40"
            >
              Confirm exchange
            </button>
          </div>
        </div>
      )}

      {step === 'result' && (
        <div className="animate-enter flex flex-col gap-5 rounded-card bg-surface p-6 text-center">
          {executeQuoteMutation.isPending && (
            <>
              <p className="text-lg font-semibold text-pending">Exchanging…</p>
              <p className="text-sm text-subtle">Waiting for the exchange to be accepted.</p>
            </>
          )}

          {executeQuoteMutation.isError && (
            <>
              <p className="text-lg font-semibold text-negative">Could not exchange</p>
              <p className="text-sm text-subtle">{executeError?.detail ?? executeQuoteMutation.error?.message}</p>
              <button
                type="button"
                onClick={resetAll}
                className="rounded-control border border-border px-4 py-2 text-sm font-medium text-muted"
              >
                Start over
              </button>
            </>
          )}

          {executeQuoteMutation.isSuccess && quote && (
            <>
              <p className="text-lg font-semibold text-pending">Exchange submitted</p>
              <p className="text-2xl font-semibold text-foreground">
                {formatMoney(quote.buyMinor, quote.buyCurrency)}
              </p>
              <p className="text-sm text-subtle">
                Your balances will update once the ledger settles this - usually within a few
                seconds.
              </p>
              <button
                type="button"
                onClick={resetAll}
                className="rounded-control border border-border px-4 py-2 text-sm font-medium text-muted"
              >
                New exchange
              </button>
            </>
          )}
        </div>
      )}
    </div>
  )
}
