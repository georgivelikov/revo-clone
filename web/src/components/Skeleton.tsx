/** A shimmering placeholder block - task 09.6: "skeletons, not spinners, on every list and
 * balance." `className` carries size/shape (height, width, rounding); the shimmer animation
 * itself lives in index.css as `.skeleton` so every usage shares one keyframe definition. */
export function Skeleton({ className = '' }: { className?: string }) {
  return <div className={`skeleton ${className}`} aria-hidden="true" />
}

/** A balance-shaped skeleton: one wide block (the amount) - matches the line-height of the
 * 3xl balance figure it stands in for, so nothing visibly reflows once the real value arrives. */
export function BalanceSkeleton() {
  return <Skeleton className="h-9 w-40 rounded-md" />
}

/** A single list-row skeleton shaped like the app's standard `rounded-card bg-surface p-4`
 * activity/spend/vault row, so a loading list keeps the exact rhythm the real list will have. */
export function RowSkeleton() {
  return (
    <div className="flex items-center justify-between rounded-card bg-surface p-4">
      <div className="flex items-center gap-3">
        <Skeleton className="h-9 w-9 rounded-full" />
        <div className="flex flex-col gap-1.5">
          <Skeleton className="h-3.5 w-28 rounded" />
          <Skeleton className="h-3 w-16 rounded" />
        </div>
      </div>
      <Skeleton className="h-3.5 w-16 rounded" />
    </div>
  )
}

/** `count` stacked {@link RowSkeleton}s, for a whole loading list/feed. */
export function RowSkeletonList({ count = 3 }: { count?: number }) {
  return (
    <div className="flex flex-col gap-2">
      {Array.from({ length: count }, (_, index) => (
        <RowSkeleton key={index} />
      ))}
    </div>
  )
}
