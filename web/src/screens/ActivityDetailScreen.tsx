import { useParams } from 'react-router-dom'

export function ActivityDetailScreen() {
  const { paymentId } = useParams()

  return (
    <div className="flex flex-col gap-4 px-5 pt-8">
      <h1 className="text-xl font-semibold text-foreground">Payment {paymentId}</h1>
      <p className="text-sm text-subtle">
        Status, timestamps and the ledger entry behind it land in task 03.9.
      </p>
    </div>
  )
}
