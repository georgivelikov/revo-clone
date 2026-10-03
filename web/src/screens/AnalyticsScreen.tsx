import { useMemo, useState } from 'react'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { RowSkeletonList, Skeleton } from '../components/Skeleton'
import type { BudgetResponse, CategorySpend, TopMerchant, TrendPoint } from '../api/analytics'
import { formatMoney } from '../api/format'
import { CATEGORY_ORDER, colorForCategory } from '../charts/categoryColors'
import { useBudgets, useCategoryDrillDown, useMonthlySummary, useTopMerchants, useTrend } from '../hooks/useAnalytics'

const TREND_MONTHS = 6

function startOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1)
}

function addMonths(date: Date, delta: number): Date {
  return new Date(date.getFullYear(), date.getMonth() + delta, 1)
}

// Matches the plain ISO date (YYYY-MM-DD) both AnalyticsQueryController's @DateTimeFormat params
// and its LocalDate-typed JSON fields (month/asOf) use - always the first of the month.
function monthKeyFromDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  return `${year}-${month}-01`
}

function formatMonthLong(date: Date): string {
  return new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' }).format(date)
}

function formatMonthShort(date: Date): string {
  return new Intl.DateTimeFormat(undefined, { month: 'short' }).format(date)
}

function lastNMonths(anchor: Date, n: number): Date[] {
  return Array.from({ length: n }, (_, index) => addMonths(anchor, -(n - 1 - index)))
}

function byCanonicalOrder(a: string, b: string): number {
  return CATEGORY_ORDER.indexOf(a) - CATEGORY_ORDER.indexOf(b)
}

// The task's own suggested heuristic for "a user has spend in more than one currency this
// month - pick one to chart" (plans/phase-06-vaults-analytics.md's Frontend section, and this
// task's own instructions): whichever currency has the largest total minor-unit figure. This
// never adds minor units *across* currencies (CLAUDE.md hard rule 1) - only totals already
// summed within one currency are compared against each other, as a display heuristic, not a
// converted "reporting currency" figure (06.8 explicitly deferred that; a real comparison would
// need fx-service's month-end rates, which analytics-service does not have wired at all).
function largestCurrency(totals: Map<string, number>): string | null {
  let best: string | null = null
  let bestTotal = -1
  for (const [currency, total] of totals) {
    if (total > bestTotal) {
      best = currency
      bestTotal = total
    }
  }
  return best
}

function sumByCurrency(rows: { currency: string; totalMinor: number }[]): Map<string, number> {
  const totals = new Map<string, number>()
  for (const row of rows) {
    totals.set(row.currency, (totals.get(row.currency) ?? 0) + row.totalMinor)
  }
  return totals
}

function MonthSelector({ monthDate, onChange }: { monthDate: Date; onChange: (date: Date) => void }) {
  const isCurrentMonth = monthKeyFromDate(monthDate) === monthKeyFromDate(startOfMonth(new Date()))

  return (
    <div className="flex items-center justify-between rounded-card bg-surface p-3">
      <button
        type="button"
        onClick={() => onChange(addMonths(monthDate, -1))}
        aria-label="Previous month"
        className="rounded-control px-3 py-1.5 text-sm text-muted transition-colors hover:bg-surface-raised"
      >
        &lsaquo;
      </button>
      <span className="text-sm font-medium text-foreground">{formatMonthLong(monthDate)}</span>
      <button
        type="button"
        onClick={() => onChange(addMonths(monthDate, 1))}
        disabled={isCurrentMonth}
        aria-label="Next month"
        className="rounded-control px-3 py-1.5 text-sm text-muted transition-colors hover:bg-surface-raised disabled:opacity-30"
      >
        &rsaquo;
      </button>
    </div>
  )
}

function CurrencyTabs({
  currencies,
  selected,
  onSelect,
}: {
  currencies: string[]
  selected: string | null
  onSelect: (currency: string) => void
}) {
  return (
    <div className="flex gap-2">
      {currencies.map((currency) => (
        <button
          key={currency}
          type="button"
          onClick={() => onSelect(currency)}
          className={`rounded-control px-3 py-1.5 text-xs font-medium transition-colors ${
            selected === currency ? 'bg-accent text-white' : 'bg-surface-raised text-muted'
          }`}
        >
          {currency}
        </button>
      ))}
    </div>
  )
}

const DONUT_SIZE = 192
const DONUT_STROKE = 30
const DONUT_RADIUS = (DONUT_SIZE - DONUT_STROKE) / 2
const DONUT_CIRCUMFERENCE = 2 * Math.PI * DONUT_RADIUS
// The 2px "surface gap" spacer between touching marks (dataviz skill's marks-and-anatomy.md),
// applied here as a sliver of unpainted arc between donut segments.
const SEGMENT_GAP = 3

/**
 * Hand-rolled SVG donut - no charting library, matching VaultsScreen's own ProgressRing
 * precedent. Slices render in the fixed canonical category order (charts/categoryColors.ts),
 * never sorted by amount: that is what keeps the ring's colour-to-position mapping recognisable
 * from month to month, and keeps the arcs on the dataviz skill's own *adjacent*-pair validation
 * (the skill validated this exact sequential hue order, not an arbitrary one a value-sort would
 * produce - see index.css's chart-palette comment).
 *
 * Tapping a slice drills in, same as tapping its row in the legend below - the legend is the
 * primary, larger hit target (see the dataviz skill's "hit target bigger than the mark"), the
 * arc itself is a bonus for pointer users. The native SVG `<title>` gives each arc a plain hover
 * tooltip; the always-visible legend is what actually carries the reading for every device.
 */
function CategoryDonut({
  categories,
  currency,
  onSelect,
}: {
  categories: CategorySpend[]
  currency: string
  onSelect: (category: string) => void
}) {
  const total = categories.reduce((sum, row) => sum + row.totalMinor, 0)
  const ordered = [...categories].sort((a, b) => byCanonicalOrder(a.category, b.category))

  interface Segment {
    category: string
    totalMinor: number
    color: string
    visibleDash: number
    offset: number
  }

  const { segments } = ordered.reduce(
    (state, row) => {
      const fraction = total > 0 ? row.totalMinor / total : 0
      const rawDash = fraction * DONUT_CIRCUMFERENCE
      const visibleDash = ordered.length > 1 ? Math.max(rawDash - SEGMENT_GAP, 0) : rawDash
      state.segments.push({
        category: row.category,
        totalMinor: row.totalMinor,
        color: colorForCategory(row.category),
        visibleDash,
        offset: -state.cumulative,
      })
      return { cumulative: state.cumulative + rawDash, segments: state.segments }
    },
    { cumulative: 0, segments: [] as Segment[] },
  )

  return (
    <div className="relative mx-auto" style={{ width: DONUT_SIZE, height: DONUT_SIZE }}>
      <svg width={DONUT_SIZE} height={DONUT_SIZE} viewBox={`0 0 ${DONUT_SIZE} ${DONUT_SIZE}`}>
        <circle
          cx={DONUT_SIZE / 2}
          cy={DONUT_SIZE / 2}
          r={DONUT_RADIUS}
          fill="none"
          stroke="var(--color-surface-raised)"
          strokeWidth={DONUT_STROKE}
        />
        <g transform={`rotate(-90 ${DONUT_SIZE / 2} ${DONUT_SIZE / 2})`}>
          {segments.map((segment) => (
            <circle
              key={segment.category}
              cx={DONUT_SIZE / 2}
              cy={DONUT_SIZE / 2}
              r={DONUT_RADIUS}
              fill="none"
              stroke={segment.color}
              strokeWidth={DONUT_STROKE}
              strokeLinecap="butt"
              strokeDasharray={`${segment.visibleDash} ${DONUT_CIRCUMFERENCE - segment.visibleDash}`}
              strokeDashoffset={segment.offset}
              onClick={() => onSelect(segment.category)}
              className="cursor-pointer"
            >
              <title>{`${segment.category}: ${formatMoney(segment.totalMinor, currency)}`}</title>
            </circle>
          ))}
        </g>
      </svg>
      <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
        <p className="text-xs text-muted">Total</p>
        <p className="text-lg font-semibold text-foreground">{formatMoney(total, currency)}</p>
      </div>
    </div>
  )
}

function CategoryLegend({
  categories,
  currency,
  onSelect,
}: {
  categories: CategorySpend[]
  currency: string
  onSelect: (category: string) => void
}) {
  const total = categories.reduce((sum, row) => sum + row.totalMinor, 0)
  const ordered = [...categories].sort((a, b) => byCanonicalOrder(a.category, b.category))

  return (
    <div className="flex flex-col gap-1">
      {ordered.map((row) => (
        <button
          key={row.category}
          type="button"
          onClick={() => onSelect(row.category)}
          className="flex items-center justify-between rounded-control px-2 py-2 text-sm transition-colors hover:bg-surface-raised"
        >
          <span className="flex items-center gap-2">
            <span
              aria-hidden="true"
              className="h-2.5 w-2.5 shrink-0 rounded-full"
              style={{ backgroundColor: colorForCategory(row.category) }}
            />
            <span className="text-foreground">{row.category}</span>
          </span>
          <span className="flex items-center gap-2">
            <span className="text-foreground">{formatMoney(row.totalMinor, currency)}</span>
            <span className="w-9 text-right text-xs text-subtle">
              {total > 0 ? Math.round((row.totalMinor / total) * 100) : 0}%
            </span>
          </span>
        </button>
      ))}
    </div>
  )
}

function DrillDownPanel({ category, month, onClose }: { category: string; month: string; onClose: () => void }) {
  const { data, isLoading, isError } = useCategoryDrillDown(category, month)

  return (
    <section className="flex flex-col gap-3 rounded-card bg-surface p-5">
      <div className="flex items-center justify-between">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <span
            aria-hidden="true"
            className="h-2.5 w-2.5 rounded-full"
            style={{ backgroundColor: colorForCategory(category) }}
          />
          {category}
        </h2>
        <button type="button" onClick={onClose} className="text-xs text-muted">
          Close
        </button>
      </div>

      {isLoading ? (
        <RowSkeletonList count={2} />
      ) : isError ? (
        <ErrorState title="Could not load these transactions." />
      ) : data && data.length > 0 ? (
        <div className="flex flex-col gap-2">
          {data.map((txn) => (
            <div key={txn.authorizationId} className="flex items-center justify-between text-sm">
              <div>
                <p className="text-foreground">{txn.merchantName}</p>
                <p className="text-xs text-subtle">
                  {new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' }).format(
                    new Date(txn.settledAt),
                  )}
                </p>
              </div>
              <span className="text-foreground">{formatMoney(txn.amountMinor, txn.currency)}</span>
            </div>
          ))}
        </div>
      ) : (
        <p className="text-sm text-subtle">No settled transactions in this category this month.</p>
      )}
    </section>
  )
}

/**
 * The 6-month trend, kept as the plain single-series bar-per-month chart the existing
 * `GET /api/analytics/trend` endpoint actually supports (task 06.8 deliberately returns one
 * overall total per month, summed across categories - see AnalyticsQueryService.trend's own
 * javadoc). Reusing that endpoint as-is, rather than fetching 6 months of `/summary` and
 * building a stacked/grouped per-category bar client-side, is this task's chosen resolution of
 * the tension in its own instructions: the trend series has no category dimension to colour by
 * in the first place, so the "same colour everywhere" rule has nothing to apply to here - it
 * meaningfully applies to the donut and the budget bars below, which are genuinely
 * category-keyed. The dataviz skill's own form guidance backs this reading directly: "one
 * series is the point, rest are context" is exactly this shape, and its prescribed treatment is
 * **emphasis** (accent the one series that is the point, de-emphasise the rest into a neutral
 * gray) - not a categorical palette, which would be the wrong tool for a chart with only one
 * true series. Here "the point" is the selected month.
 */
function TrendChart({ points, anchor, currency }: { points: TrendPoint[]; anchor: Date; currency: string | null }) {
  if (!currency) {
    return <p className="text-sm text-subtle">No spend history yet.</p>
  }

  const anchorKey = monthKeyFromDate(anchor)
  const byMonth = new Map(points.filter((point) => point.currency === currency).map((point) => [point.month, point]))
  const months = lastNMonths(anchor, TREND_MONTHS)
  const bars = months.map((date) => {
    const key = monthKeyFromDate(date)
    return { key, date, point: byMonth.get(key) ?? null }
  })
  const maxMinor = Math.max(1, ...bars.map((bar) => bar.point?.totalMinor ?? 0))

  return (
    <div className="flex items-end gap-2 rounded-card bg-surface p-4">
      {bars.map(({ key, date, point }) => {
        const value = point?.totalMinor ?? 0
        const isCurrent = key === anchorKey
        const heightPct = value > 0 ? Math.max((value / maxMinor) * 100, 4) : 0
        return (
          <div key={key} className="flex flex-1 flex-col items-center gap-1.5">
            <span className="h-3 text-[10px] font-medium text-foreground">
              {isCurrent ? formatMoney(value, currency) : ''}
            </span>
            <div className="flex h-24 w-full items-end" title={formatMoney(value, currency)}>
              <div
                className="w-full rounded-t-md transition-all"
                style={{
                  height: `${heightPct}%`,
                  backgroundColor: isCurrent ? 'var(--color-accent)' : 'var(--color-subtle)',
                }}
              />
            </div>
            <span className="text-[10px] text-subtle">{formatMonthShort(date)}</span>
          </div>
        )
      })}
    </div>
  )
}

/**
 * One bar per category with a budget set for the selected month/currency. The bar's *base*
 * colour is the category's own identity colour (the same one the donut and legend just used for
 * it) so a glance still tells you which category this is even before reading the label; crossing
 * 80%/100% swaps the *fill* to this app's existing amber/red semantic tokens (--color-pending /
 * --color-negative, the same ones ActivityScreen already uses for status) as a threshold-state
 * overlay on top of that identity, never a replacement for it - the small category-coloured dot
 * beside the label stays the category's colour regardless of state, so identity is never lost
 * even while the bar itself is shouting "over budget" in red.
 */
function BudgetBars({
  budgets,
  categorySpend,
  currency,
}: {
  budgets: BudgetResponse[]
  categorySpend: Map<string, number>
  currency: string | null
}) {
  const relevant = currency
    ? [...budgets].filter((budget) => budget.currency === currency).sort((a, b) => byCanonicalOrder(a.category, b.category))
    : []

  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-sm font-medium text-muted">Budgets</h2>
      {relevant.length === 0 ? (
        <p className="text-sm text-subtle">No budgets set for this month yet.</p>
      ) : (
        <div className="flex flex-col gap-3">
          {relevant.map((budget) => {
            const spentMinor = categorySpend.get(budget.category) ?? 0
            const pct = budget.limitMinor > 0 ? (spentMinor / budget.limitMinor) * 100 : 0
            const isOver = pct >= 100
            const isWarning = !isOver && pct >= 80
            const fillColor = isOver
              ? 'var(--color-negative)'
              : isWarning
                ? 'var(--color-pending)'
                : colorForCategory(budget.category)

            return (
              <div key={budget.id} className="flex flex-col gap-1.5 rounded-card bg-surface p-4">
                <div className="flex items-center justify-between text-sm">
                  <span className="flex items-center gap-2 text-foreground">
                    <span
                      aria-hidden="true"
                      className="h-2.5 w-2.5 shrink-0 rounded-full"
                      style={{ backgroundColor: colorForCategory(budget.category) }}
                    />
                    {budget.category}
                  </span>
                  <span className="text-xs text-muted">
                    {formatMoney(spentMinor, currency!)} of {formatMoney(budget.limitMinor, currency!)}
                  </span>
                </div>
                <div className="h-2 w-full overflow-hidden rounded-full bg-surface-raised">
                  <div
                    className="h-full rounded-full transition-all"
                    style={{ width: `${Math.min(pct, 100)}%`, backgroundColor: fillColor }}
                  />
                </div>
                {(isOver || isWarning) && (
                  <p className={`text-xs ${isOver ? 'text-negative' : 'text-pending'}`}>
                    {isOver ? 'Over budget' : `${Math.round(pct)}% of budget used`}
                  </p>
                )}
              </div>
            )
          })}
        </div>
      )}
    </section>
  )
}

function TopMerchantsList({ merchants, currency }: { merchants: TopMerchant[]; currency: string | null }) {
  const filtered = currency ? merchants.filter((merchant) => merchant.currency === currency) : merchants

  if (filtered.length === 0) {
    return <p className="text-sm text-subtle">No merchant spend yet this month.</p>
  }

  return (
    <div className="flex flex-col gap-2">
      {filtered.map((merchant) => (
        <div
          key={`${merchant.merchantName}-${merchant.currency}`}
          className="flex items-center justify-between rounded-card bg-surface p-3 text-sm"
        >
          <div>
            <p className="text-foreground">{merchant.merchantName}</p>
            <p className="text-xs text-subtle">
              {merchant.txnCount} transaction{merchant.txnCount === 1 ? '' : 's'}
            </p>
          </div>
          <span className="font-medium text-foreground">{formatMoney(merchant.totalMinor, merchant.currency)}</span>
        </div>
      ))}
    </div>
  )
}

export function AnalyticsScreen() {
  const [monthDate, setMonthDate] = useState(() => startOfMonth(new Date()))
  const [currencyOverride, setCurrencyOverride] = useState<string | null>(null)
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null)
  // Tracks the month a drill-down/currency choice was made for - compared against the current
  // month on every render (React's documented "adjust state while rendering" pattern) rather
  // than a useEffect, so switching months resets both in the same render instead of a
  // wasted extra one. A new month starts with no category drilled into and no forced currency -
  // the previous month's currency choice may not even exist in the new one.
  const [selectionMonthKey, setSelectionMonthKey] = useState(() => monthKeyFromDate(startOfMonth(new Date())))

  const monthKey = monthKeyFromDate(monthDate)

  if (monthKey !== selectionMonthKey) {
    setSelectionMonthKey(monthKey)
    setSelectedCategory(null)
    setCurrencyOverride(null)
  }

  const summary = useMonthlySummary(monthKey)
  const trend = useTrend(monthKey)
  const merchants = useTopMerchants(monthKey)
  const budgets = useBudgets()

  const summaryCurrencyTotals = useMemo(() => sumByCurrency(summary.data?.categories ?? []), [summary.data])
  const trendCurrencyTotals = useMemo(() => sumByCurrency(trend.data ?? []), [trend.data])

  const availableCurrencies = useMemo(
    () => Array.from(new Set([...summaryCurrencyTotals.keys(), ...trendCurrencyTotals.keys()])).sort(),
    [summaryCurrencyTotals, trendCurrencyTotals],
  )

  // Prefer the selected month's own largest currency; fall back to the trend window's largest
  // so the trend chart (and the currency switcher) still has something to show on a quiet month.
  const defaultCurrency = largestCurrency(summaryCurrencyTotals) ?? largestCurrency(trendCurrencyTotals)
  const selectedCurrency =
    currencyOverride && availableCurrencies.includes(currencyOverride) ? currencyOverride : defaultCurrency

  const categoriesForCurrency = useMemo(
    () => (summary.data?.categories ?? []).filter((row) => row.currency === selectedCurrency),
    [summary.data, selectedCurrency],
  )

  const categorySpendByCategory = useMemo(() => {
    const map = new Map<string, number>()
    for (const row of categoriesForCurrency) {
      map.set(row.category, row.totalMinor)
    }
    return map
  }, [categoriesForCurrency])

  return (
    <div className="flex flex-col gap-6 px-5 pb-6 pt-8">
      <h1 className="text-xl font-semibold text-foreground">Analytics</h1>

      <MonthSelector monthDate={monthDate} onChange={setMonthDate} />

      {availableCurrencies.length > 1 && (
        <CurrencyTabs currencies={availableCurrencies} selected={selectedCurrency} onSelect={setCurrencyOverride} />
      )}

      {summary.isLoading ? (
        <div className="flex flex-col items-center gap-4 rounded-card bg-surface p-5">
          <Skeleton className="h-48 w-48 rounded-full" />
          <RowSkeletonList count={3} />
        </div>
      ) : summary.isError ? (
        <ErrorState title="Could not load this month's spend." onRetry={() => void summary.refetch()} />
      ) : selectedCategory ? (
        <DrillDownPanel category={selectedCategory} month={monthKey} onClose={() => setSelectedCategory(null)} />
      ) : categoriesForCurrency.length > 0 && selectedCurrency ? (
        <section className="flex flex-col gap-4 rounded-card bg-surface p-5">
          <CategoryDonut categories={categoriesForCurrency} currency={selectedCurrency} onSelect={setSelectedCategory} />
          <CategoryLegend categories={categoriesForCurrency} currency={selectedCurrency} onSelect={setSelectedCategory} />
        </section>
      ) : (
        <EmptyState icon="◔" title="No categorised spend this month yet" description="Card taps settle into a category automatically once they clear." />
      )}

      <section className="flex flex-col gap-3">
        <h2 className="text-sm font-medium text-muted">Last {TREND_MONTHS} months</h2>
        {trend.isLoading ? (
          <Skeleton className="h-28 w-full rounded-card" />
        ) : (
          <TrendChart points={trend.data ?? []} anchor={monthDate} currency={selectedCurrency} />
        )}
      </section>

      <BudgetBars
        budgets={(budgets.data ?? []).filter((budget) => budget.month === monthKey)}
        categorySpend={categorySpendByCategory}
        currency={selectedCurrency}
      />

      <section className="flex flex-col gap-3">
        <h2 className="text-sm font-medium text-muted">Top merchants</h2>
        {merchants.isLoading ? (
          <RowSkeletonList count={3} />
        ) : (
          <TopMerchantsList merchants={merchants.data ?? []} currency={selectedCurrency} />
        )}
      </section>
    </div>
  )
}
