import { useQuery } from '@tanstack/react-query'
import type { RateTickFrame } from '../ws/rateTick'

export const RATE_TICKS_QUERY_KEY = ['fx', 'rates'] as const

// There is no GET for "the current rate" - a tick only ever arrives by push, over
// /topic/rates (useNotificationSocket's applyRateTick writes it here). `enabled: false` is
// therefore load-bearing, not an oversight: it keeps this query from ever calling queryFn again
// after the first mount - no refetch on window focus, no refetch on reconnect - so nothing here
// can silently clobber a live ticks map with an empty one. `initialData` seeds an empty map
// before the first tick arrives, without counting as a fetch.
export function useRateTicks(): Record<string, RateTickFrame> {
  const { data } = useQuery<Record<string, RateTickFrame>>({
    queryKey: RATE_TICKS_QUERY_KEY,
    queryFn: () => ({}),
    initialData: {},
    enabled: false,
  })
  return data
}

/** The mid rate for one canonical pair (e.g. "EUR/GBP"), if a tick has arrived for it yet. */
export function useRateTick(pair: string): RateTickFrame | undefined {
  return useRateTicks()[pair]
}
