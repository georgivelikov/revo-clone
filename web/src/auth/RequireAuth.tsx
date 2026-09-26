import type { ReactNode } from 'react'
import { useAuth } from 'react-oidc-context'
import { LoginScreen } from '../screens/LoginScreen'

function FullScreenStatus({ message }: { message: string }) {
  return (
    <div className="flex h-dvh items-center justify-center bg-canvas px-6 text-center text-muted">
      {message}
    </div>
  )
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const auth = useAuth()

  if (auth.isLoading) {
    return <FullScreenStatus message="Loading…" />
  }

  if (auth.error) {
    return <FullScreenStatus message={`Authentication error: ${auth.error.message}`} />
  }

  if (!auth.isAuthenticated) {
    return <LoginScreen />
  }

  return <>{children}</>
}
