import { CATEGORIES } from '../api/analytics'

// The fixed slice order for the donut, and the order colours were assigned in - both drawn from
// plans/phase-06-vaults-analytics.md's "Categorisation" section, in that section's own order
// (mirrored as CATEGORIES in api/analytics.ts). Rendering the donut in this fixed order rather
// than sorted by amount keeps the ring's colour-to-position mapping consistent month to month
// (the dataviz skill's own worst-adjacent-pair numbers were validated for a fixed sequential
// order - see index.css's chart-palette comment - so this also keeps the chart on the validated
// adjacency, never an arbitrary one produced by sorting by value).
export const CATEGORY_ORDER: readonly string[] = CATEGORIES

// One fixed colour per category name, stable across the donut, the trend chart and the budget
// bars - plans/phase-06-vaults-analytics.md's explicit "a category is the same colour
// everywhere" instruction. Only the first eight categories get one of the dataviz skill's eight
// validated categorical hues (index.css's --color-chart-1..8); the remaining four
// (Transfers, Cash, Income, Other) share the app's own neutral --color-subtle token rather than
// a generated ninth hue, per the skill's series-count ladder ("7-8: token ceiling; past it, fold
// the tail into 'Other'") - a natural fit here since "Other" is already this app's own
// designated overflow category. Each of the four keeps its own text label everywhere it is
// shown, so identity is never lost even though the swatch colour is shared.
const CATEGORY_COLORS: Record<string, string> = {
  Groceries: 'var(--color-chart-1)',
  Restaurants: 'var(--color-chart-2)',
  Transport: 'var(--color-chart-3)',
  Shopping: 'var(--color-chart-4)',
  Entertainment: 'var(--color-chart-5)',
  Bills: 'var(--color-chart-6)',
  Health: 'var(--color-chart-7)',
  Travel: 'var(--color-chart-8)',
  Transfers: 'var(--color-subtle)',
  Cash: 'var(--color-subtle)',
  Income: 'var(--color-subtle)',
  Other: 'var(--color-subtle)',
}

const FALLBACK_COLOR = 'var(--color-subtle)'

export function colorForCategory(category: string): string {
  return CATEGORY_COLORS[category] ?? FALLBACK_COLOR
}
