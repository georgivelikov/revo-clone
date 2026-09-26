// `GET /api/payments/recipients` (recent + searchable counterparties) is not implemented
// server-side yet, so the recipient step falls back to a per-device, per-user memory of
// accounts this browser has sent to before. This is a client-only convenience, not the real
// counterparty search - it disappears if the user switches devices or clears storage.
export interface RecentRecipient {
  accountId: string
  currency: string
  label: string
  lastUsedAt: string
}

const STORAGE_KEY_PREFIX = 'revolutclone.recentRecipients.'
const MAX_RECENTS = 8

function storageKey(userId: string): string {
  return `${STORAGE_KEY_PREFIX}${userId}`
}

export function loadRecentRecipients(userId: string): RecentRecipient[] {
  try {
    const raw = localStorage.getItem(storageKey(userId))
    return raw ? (JSON.parse(raw) as RecentRecipient[]) : []
  } catch {
    return []
  }
}

export function rememberRecipient(userId: string, recipient: RecentRecipient): void {
  const withoutDuplicate = loadRecentRecipients(userId).filter(
    (entry) => entry.accountId !== recipient.accountId,
  )
  const updated = [recipient, ...withoutDuplicate].slice(0, MAX_RECENTS)
  localStorage.setItem(storageKey(userId), JSON.stringify(updated))
}
