import { useEffect, useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import type { RevealCardResponse } from '../api/cards'
import { revealCard } from '../api/cards'

const AUTO_HIDE_MS = 30_000

/**
 * plans/phase-05-cards.md's Card screen note: the revealed PAN "auto-hides after 30 s and is
 * never put in clipboard history without an explicit action." The countdown lives here rather
 * than in the screen, so the timer's lifetime is tied to the mutation's own success rather than
 * to the component staying mounted with its own effect to get right twice.
 */
export function useCardReveal() {
  const auth = useAuth()
  const accessToken = auth.user?.access_token
  const [secondsRemaining, setSecondsRemaining] = useState<number | null>(null)
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const mutation = useMutation({
    mutationFn: ({ cardId, pin }: { cardId: string; pin: string }) => revealCard(accessToken!, cardId, pin),
    onSuccess: () => {
      setSecondsRemaining(AUTO_HIDE_MS / 1000)
      hideTimerRef.current = setTimeout(() => hide(), AUTO_HIDE_MS)
      tickRef.current = setInterval(
        () => setSecondsRemaining((seconds) => (seconds === null ? null : Math.max(0, seconds - 1))),
        1_000,
      )
    },
  })

  function hide() {
    if (hideTimerRef.current) clearTimeout(hideTimerRef.current)
    if (tickRef.current) clearInterval(tickRef.current)
    hideTimerRef.current = null
    tickRef.current = null
    setSecondsRemaining(null)
    mutation.reset()
  }

  useEffect(
    () => () => {
      if (hideTimerRef.current) clearTimeout(hideTimerRef.current)
      if (tickRef.current) clearInterval(tickRef.current)
    },
    [],
  )

  return {
    reveal: (cardId: string, pin: string) => mutation.mutate({ cardId, pin }),
    hide,
    revealed: (mutation.data ?? null) as RevealCardResponse | null,
    isRevealing: mutation.isPending,
    error: mutation.error,
    secondsRemaining,
    reset: mutation.reset,
  }
}
