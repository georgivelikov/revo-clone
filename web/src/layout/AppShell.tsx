import type { ReactNode } from 'react'
import { ConnectionBanner } from '../components/ConnectionBanner'
import type { ConnectionStatus } from '../ws/notificationSocket'
import { BottomTabBar } from './BottomTabBar'

export function AppShell({
  children,
  connectionStatus = null,
}: {
  children: ReactNode
  connectionStatus?: ConnectionStatus | null
}) {
  return (
    <div className="flex h-dvh justify-center bg-canvas">
      <div className="flex w-full max-w-[var(--shell-max-width)] flex-col">
        <ConnectionBanner status={connectionStatus} />
        <main className="flex-1 overflow-y-auto">{children}</main>
        <BottomTabBar />
      </div>
    </div>
  )
}
