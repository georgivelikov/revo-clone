import { useAuth } from 'react-oidc-context'

export function LoginScreen() {
  const auth = useAuth()

  return (
    <div className="flex h-dvh flex-col items-center justify-center gap-10 bg-canvas px-6 text-center">
      <div>
        <p className="text-sm uppercase tracking-[0.3em] text-muted">Revolut Clone</p>
        <h1 className="mt-3 text-3xl font-semibold text-foreground">Money, moving</h1>
      </div>
      <button
        type="button"
        onClick={() => void auth.signinRedirect()}
        className="w-full max-w-xs rounded-control bg-accent px-6 py-3 font-medium text-white transition-colors hover:bg-accent-hover"
      >
        Log in
      </button>
    </div>
  )
}
