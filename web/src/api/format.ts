// Display-only: converts minor units to a formatted major-unit string using each currency's
// own exponent (e.g. 2 for EUR/GBP, 0 for JPY) rather than assuming cents everywhere.
export function formatMoney(amountMinor: number, currency: string): string {
  const formatter = new Intl.NumberFormat(undefined, { style: 'currency', currency })
  const fractionDigits = formatter.resolvedOptions().maximumFractionDigits ?? 2
  const major = amountMinor / 10 ** fractionDigits
  return formatter.format(major)
}
