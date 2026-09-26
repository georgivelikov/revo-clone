import { useAuth } from 'react-oidc-context'

export function HomeScreen() {
  const auth = useAuth()

  return (
    <div className="flex flex-col gap-6 px-5 pb-6 pt-8">
      <header className="flex items-center justify-between">
        <div>
          <p className="text-sm text-muted">Welcome back</p>
          <h1 className="text-xl font-semibold text-foreground">
            {auth.user?.profile.name ?? auth.user?.profile.email ?? 'there'}
          </h1>
        </div>
        <button
          type="button"
          onClick={() => void auth.signoutRedirect()}
          className="rounded-control border border-border px-3 py-1.5 text-xs font-medium text-muted transition-colors hover:text-foreground"
        >
          Log out
        </button>
      </header>

      <section className="rounded-card bg-surface p-6">
        <p className="text-sm text-muted">Total balance</p>
        <p className="mt-1 text-3xl font-semibold text-foreground">– . –</p>
        <p className="mt-4 text-xs text-subtle">Wired up to the accounts API in task 03.8.</p>
      </section>

      <section>
        <h2 className="text-sm font-medium text-muted">Recent activity</h2>
        <div className="mt-3 rounded-card bg-surface p-6 text-sm text-subtle">Nothing yet.</div>
      </section>
    </div>
  )
}
