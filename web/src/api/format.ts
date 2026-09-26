// Display-only: converts minor units to a formatted major-unit string using each currency's
// own exponent (e.g. 2 for EUR/GBP, 0 for JPY) rather than assuming cents everywhere.
export function formatMoney(amountMinor: number, currency: string): string {
  const formatter = new Intl.NumberFormat(undefined, { style: 'currency', currency })
  const fractionDigits = formatter.resolvedOptions().maximumFractionDigits ?? 2
  const major = amountMinor / 10 ** fractionDigits
  return formatter.format(major)
}

// Inverse of formatMoney: a user-typed major-unit amount to minor units, using the currency's
// own exponent (so JPY input isn't silently treated as having cents). Returns null for anything
// that is not a positive, finite amount rather than throwing, since this runs on every keystroke.
export function parseMoneyToMinor(input: string, currency: string): number | null {
  const trimmed = input.trim().replace(',', '.')
  if (trimmed === '') {
    return null
  }
  const major = Number(trimmed)
  if (!Number.isFinite(major) || major <= 0) {
    return null
  }
  const formatter = new Intl.NumberFormat(undefined, { style: 'currency', currency })
  const fractionDigits = formatter.resolvedOptions().maximumFractionDigits ?? 2
  return Math.round(major * 10 ** fractionDigits)
}
