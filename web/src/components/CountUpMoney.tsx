import { formatMoney } from '../api/format'
import { useCountUp } from '../hooks/useCountUp'

/** `formatMoney`, but counting up from whatever it last rendered - task 09.6. */
export function CountUpMoney({
  amountMinor,
  currency,
  className,
}: {
  amountMinor: number
  currency: string
  className?: string
}) {
  const displayed = useCountUp(amountMinor)
  return <span className={className}>{formatMoney(displayed ?? amountMinor, currency)}</span>
}
