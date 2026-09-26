import type { ReactNode } from 'react'
import { BottomTabBar } from './BottomTabBar'

export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="flex h-dvh justify-center bg-canvas">
      <div className="flex w-full max-w-[var(--shell-max-width)] flex-col">
        <main className="flex-1 overflow-y-auto">{children}</main>
        <BottomTabBar />
      </div>
    </div>
  )
}
