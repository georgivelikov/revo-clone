/** task 09.6: "a failed [load] shows the reason and a retry, never a raw stack." `detail` is
 * meant to be a server-provided problem-detail message when one exists, never a raw
 * `Error.message`/stack - callers pass whichever human-readable string they already show
 * elsewhere (see TransferScreen/ExchangeScreen's own `ApiError.detail` handling). */
export function ErrorState({
  title,
  detail,
  onRetry,
}: {
  title: string
  detail?: string
  onRetry?: () => void
}) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-card bg-surface px-6 py-10 text-center">
      <span aria-hidden="true" className="text-3xl">
        ⚠
      </span>
      <div className="flex flex-col gap-1">
        <p className="text-sm font-medium text-negative">{title}</p>
        {detail && <p className="text-xs text-subtle">{detail}</p>}
      </div>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="rounded-control border border-border px-4 py-2 text-sm font-medium text-muted transition-colors hover:text-foreground"
        >
          Try again
        </button>
      )}
    </div>
  )
}
