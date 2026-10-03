import { useEffect, useRef, useState } from 'react'

const DURATION_MS = 500

/**
 * Animates an integer value from its previous render to `target` over half a second -
 * task 09.6: "the balance number should count up, not jump." Purely a display interpolation:
 * every intermediate value is itself rounded to a whole minor unit (never a fraction of one),
 * so this never introduces float arithmetic into an actual money value - it only eases what a
 * `formatMoney` caller renders from frame to frame, same long-minor-units contract throughout.
 *
 * Skips the animation (snaps straight to `target`) on the first render a real number appears -
 * counting up from a skeleton/placeholder's absence makes no sense, only from one real balance
 * to the next.
 */
export function useCountUp(target: number | null): number | null {
  const [displayed, setDisplayed] = useState(target)
  const previousRef = useRef(target)
  const frameRef = useRef<number | null>(null)

  useEffect(() => {
    if (target === null) {
      // No setState here - `displayed` can keep whatever it last held; the returned value
      // below is derived straight from `target` for this case, not from state.
      previousRef.current = null
      return
    }
    const from = previousRef.current
    if (from === null || from === target) {
      previousRef.current = target
      setDisplayed(target)
      return
    }

    const startedAt = performance.now()
    // An arrow function, not a `function` declaration: TypeScript only narrows `from`/`target`
    // (both just proven non-null above) inside a closure it can see is called from this same
    // scope - a hoistable function declaration loses that narrowing, an arrow function keeps it.
    const tick = (now: number) => {
      const elapsed = now - startedAt
      const progress = Math.min(1, elapsed / DURATION_MS)
      // easeOutCubic - fast start, gentle settle, rather than a linear ramp.
      const eased = 1 - (1 - progress) ** 3
      setDisplayed(Math.round(from + (target - from) * eased))
      if (progress < 1) {
        frameRef.current = requestAnimationFrame(tick)
      } else {
        previousRef.current = target
      }
    }
    frameRef.current = requestAnimationFrame(tick)
    return () => {
      if (frameRef.current !== null) {
        cancelAnimationFrame(frameRef.current)
      }
    }
  }, [target])

  return target === null ? null : displayed
}
