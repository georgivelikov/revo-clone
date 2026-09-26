// Mirrors notification-service's ClientEvent record - the one shape that travels over
// /user/{userId}/queue/events. Two kinds of frame share it (see that record's javadoc):
// persisted feed frames (MONEY_IN, MONEY_OUT, TRANSFER_FAILED, PUSH) carry a notificationId;
// transient status frames (PAYMENT_PENDING, PAYMENT_COMPLETED) do not, since nothing is
// persisted for them - they exist only to settle the optimistic row this client inserted.
export interface ClientEvent {
  type: string
  occurredAt: string
  correlationId: string
  notificationId: string | null
  paymentId: string | null
  title: string | null
  body: string | null
  amountMinor: number | null
  currency: string | null
  balanceAfterMinor: number | null
  status: string | null
  reason: string | null
}

export const PAYMENT_PENDING = 'PAYMENT_PENDING'
export const PAYMENT_COMPLETED = 'PAYMENT_COMPLETED'
export const TRANSFER_FAILED = 'TRANSFER_FAILED'
