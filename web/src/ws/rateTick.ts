// Mirrors notification-service's RateTickFrame record - the shape on /topic/rates.
// pair is always the alphabetical ordering of the two ISO currency codes (e.g. "EUR/GBP"),
// per FxRateTickV1's own contract - never "GBP/EUR".
export interface RateTickFrame {
  pair: string
  mid: string
  asOf: string
}
