// Display-only mirror of the backend's canonical-pair rule (FxRateTickV1's own contract): the
// alphabetical ordering of the two codes, e.g. "EUR/GBP", never "GBP/EUR". Storing/reading only
// this direction and inverting on demand is what stops the two directions from ever disagreeing
// with each other - see fx-service's own FxRateCache for the authoritative version of this idea.
export function canonicalPair(a: string, b: string): string {
  return a < b ? `${a}/${b}` : `${b}/${a}`
}

// True when (a, b) runs opposite to the canonical (alphabetical) ordering, and the ticked mid
// therefore needs inverting before it means "a per b" rather than "b per a".
export function isInvertedOrder(a: string, b: string): boolean {
  return a > b
}

// Display-only inversion of a decimal-string rate. Never used for anything that posts or moves
// money - CLAUDE.md hard rule 1 confines that arithmetic to common-money's FxMath, which this
// ticker has no access to and no need of: a quote's actual client rate always comes from the
// quote response itself, never from this indicative, pre-quote display.
export function invertForDisplay(mid: string): string {
  const value = Number(mid)
  if (!Number.isFinite(value) || value === 0) {
    return mid
  }
  return (1 / value).toFixed(10)
}

const FLAGS: Record<string, string> = {
  EUR: '🇪🇺',
  GBP: '🇬🇧',
  USD: '🇺🇸',
  JPY: '🇯🇵',
}

export function currencyFlag(currency: string): string {
  return FLAGS[currency] ?? '💱'
}
