import { useState } from 'react'
import { formatMoney, parseMoneyToMinor } from '../api/format'
import { ApiError } from '../api/http'
import type { VaultResponse, VaultTransferDirection } from '../api/vaults'
import { useAccountsWithBalances } from '../hooks/useAccountsWithBalances'
import { useCards } from '../hooks/useCards'
import { useCreateVault, useUpdateVault, useVaultTransfer, useVaults } from '../hooks/useVaults'

// No "round-ups this month" total on this screen, deliberately: nothing in phase 06's completed
// work (through task 06.8) tags an individual payment or ledger entry as "this was a round-up"
// in a way a client can query - RoundUpService posts round-up transfers the same way any other
// vault move-in is posted, with no distinguishing flag. Faking the number with a heuristic (e.g.
// matching on a payment reference string) would be exactly the kind of fragile guess CLAUDE.md's
// working style warns against, so it is left out; a real total needs a backend tag/endpoint this
// phase does not provide, worth flagging for whoever picks up phase 06 follow-ups.

const RING_SIZE = 64
const RING_STROKE = 6
const RING_RADIUS = (RING_SIZE - RING_STROKE) / 2
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS

/** A simple SVG progress ring - no charting library needed for a single ring. `progress` is
 * clamped to [0, 1]; `null` (no goal set) renders a dim, static full circle rather than a bar
 * claiming progress toward a number that does not exist. */
function ProgressRing({ progress }: { progress: number | null }) {
  const clamped = progress === null ? 1 : Math.max(0, Math.min(1, progress))
  const offset = RING_CIRCUMFERENCE * (1 - clamped)
  return (
    <svg width={RING_SIZE} height={RING_SIZE} viewBox={`0 0 ${RING_SIZE} ${RING_SIZE}`} className="shrink-0">
      <circle
        cx={RING_SIZE / 2}
        cy={RING_SIZE / 2}
        r={RING_RADIUS}
        fill="none"
        stroke="var(--color-surface-raised)"
        strokeWidth={RING_STROKE}
      />
      <circle
        cx={RING_SIZE / 2}
        cy={RING_SIZE / 2}
        r={RING_RADIUS}
        fill="none"
        stroke={progress === null ? 'var(--color-subtle)' : 'var(--color-accent)'}
        strokeWidth={RING_STROKE}
        strokeLinecap="round"
        strokeDasharray={RING_CIRCUMFERENCE}
        strokeDashoffset={offset}
        transform={`rotate(-90 ${RING_SIZE / 2} ${RING_SIZE / 2})`}
        className="transition-all duration-500"
      />
    </svg>
  )
}

const DIRECTIONS: { direction: VaultTransferDirection; label: string }[] = [
  { direction: 'IN', label: 'Move in' },
  { direction: 'OUT', label: 'Move out' },
]

function TransferModal({
  vault,
  balanceMinor,
  onClose,
}: {
  vault: VaultResponse
  balanceMinor: number | null
  onClose: () => void
}) {
  const { accounts } = useAccountsWithBalances()
  // A vault moves money to/from one of the user's *wallets*, never another vault - the backend
  // itself only ever names walletAccountId (VaultTransferRequest.java), so vault-type accounts
  // are excluded from the picker here too.
  const wallets = accounts.filter((account) => account.type === 'WALLET' && account.state === 'ACTIVE')
  const [direction, setDirection] = useState<VaultTransferDirection>('IN')
  const [walletAccountId, setWalletAccountId] = useState(wallets[0]?.id ?? '')
  const [amountInput, setAmountInput] = useState('')
  const transfer = useVaultTransfer()
  const apiError = transfer.error instanceof ApiError ? transfer.error : null

  if (wallets.length > 0 && !walletAccountId) {
    setWalletAccountId(wallets[0].id)
  }

  const amountMinor = parseMoneyToMinor(amountInput, vault.currency)
  const wallet = wallets.find((account) => account.id === walletAccountId)
  const insufficientBalance =
    direction === 'OUT' && balanceMinor !== null && amountMinor !== null && amountMinor > balanceMinor

  function submit() {
    if (!walletAccountId || amountMinor === null) {
      return
    }
    // Client-generated, kept only for this one attempt - matching TransferScreen's convention
    // (plans/phase-03-payments-and-ui.md): a retry of a failed submit should reuse it, a new
    // submit should not. This modal is simple enough that "new submit" always means "closed and
    // reopened", so a single generated-at-mount key covers it.
    transfer.mutate(
      {
        vaultId: vault.id,
        idempotencyKey: crypto.randomUUID(),
        request: { direction, walletAccountId, amountMinor, currency: vault.currency },
      },
      { onSuccess: onClose },
    )
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center px-5">
      <button type="button" aria-label="Close" className="absolute inset-0 bg-black/60" onClick={onClose} />
      <div className="relative flex w-full max-w-xs flex-col gap-4 rounded-card bg-surface p-6">
        <h2 className="text-sm font-semibold text-foreground">{vault.name}</h2>

        <div className="flex gap-2">
          {DIRECTIONS.map(({ direction: candidate, label }) => (
            <button
              key={candidate}
              type="button"
              onClick={() => setDirection(candidate)}
              className={`flex-1 rounded-control px-3 py-1.5 text-xs font-medium transition-colors ${
                direction === candidate ? 'bg-accent text-white' : 'bg-surface-raised text-muted'
              }`}
            >
              {label}
            </button>
          ))}
        </div>

        {wallets.length === 0 ? (
          <p className="text-xs text-negative">You need an active wallet to move money.</p>
        ) : (
          <label className="flex flex-col gap-1 text-xs text-muted">
            {direction === 'IN' ? 'From wallet' : 'To wallet'}
            <select
              value={walletAccountId}
              onChange={(event) => setWalletAccountId(event.target.value)}
              className="rounded-control border border-border bg-canvas px-3 py-2 text-sm text-foreground"
            >
              {wallets.map((account) => (
                <option key={account.id} value={account.id}>
                  {account.currency} · {account.name}
                </option>
              ))}
            </select>
          </label>
        )}

        <label className="flex flex-col gap-1 text-xs text-muted">
          Amount ({vault.currency})
          <input
            inputMode="decimal"
            value={amountInput}
            onChange={(event) => setAmountInput(event.target.value)}
            placeholder="0.00"
            className="rounded-control border border-border bg-canvas px-3 py-2 text-lg text-foreground"
            autoFocus
          />
        </label>

        {direction === 'OUT' && balanceMinor !== null && (
          <p className="text-xs text-subtle">In vault: {formatMoney(balanceMinor, vault.currency)}</p>
        )}
        {insufficientBalance && (
          <p className="text-xs text-pending">More than is currently in this vault.</p>
        )}
        {wallet && wallet.currency !== vault.currency && (
          <p className="text-xs text-pending">
            {wallet.currency} wallet, {vault.currency} vault - payment-service will reject a
            currency mismatch rather than convert it silently.
          </p>
        )}
        {apiError && <p className="text-xs text-negative">{apiError.detail ?? apiError.message}</p>}

        <div className="flex gap-3">
          <button
            type="button"
            onClick={onClose}
            className="flex-1 rounded-control border border-border px-4 py-2 text-sm font-medium text-muted"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={!walletAccountId || amountMinor === null || transfer.isPending}
            onClick={submit}
            className="flex-1 rounded-control bg-accent px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover disabled:opacity-40"
          >
            {transfer.isPending ? 'Sending…' : 'Confirm'}
          </button>
        </div>
      </div>
    </div>
  )
}

// 1x is the only real option - card-service/analytics-service's schema has no
// roundup_multiplier column yet (see Vault.java's javadoc), so 2x/5x are shown, disabled, only
// to signal the feature exists rather than quietly promising something the backend ignores.
const ROUNDUP_MULTIPLIERS = [1, 2, 5]

function RoundUpSettings({ vault }: { vault: VaultResponse }) {
  const { data: cards } = useCards()
  const update = useUpdateVault(vault.id)
  const [sourceCardId, setSourceCardId] = useState(vault.roundupSourceCardId ?? cards?.[0]?.id ?? '')

  function toggle(enabled: boolean) {
    update.mutate({
      goalMinor: vault.goalMinor,
      roundupEnabled: enabled,
      roundupSourceCardId: enabled ? sourceCardId || null : vault.roundupSourceCardId,
    })
  }

  function changeSourceCard(cardId: string) {
    setSourceCardId(cardId)
    if (vault.roundupEnabled) {
      update.mutate({ goalMinor: vault.goalMinor, roundupEnabled: true, roundupSourceCardId: cardId || null })
    }
  }

  return (
    <section className="flex flex-col gap-3 rounded-card bg-surface p-5">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium text-muted">Round-ups</h3>
        <button
          type="button"
          disabled={update.isPending || !cards || cards.length === 0}
          onClick={() => toggle(!vault.roundupEnabled)}
          className={`rounded-control px-3 py-1.5 text-xs font-medium transition-colors disabled:opacity-40 ${
            vault.roundupEnabled ? 'bg-accent text-white' : 'bg-surface-raised text-muted'
          }`}
        >
          {vault.roundupEnabled ? 'On' : 'Off'}
        </button>
      </div>

      {!cards || cards.length === 0 ? (
        <p className="text-xs text-subtle">Issue a card first to round up its spend into this vault.</p>
      ) : (
        <label className="flex flex-col gap-1 text-xs text-muted">
          Source card
          <select
            value={sourceCardId}
            onChange={(event) => changeSourceCard(event.target.value)}
            className="rounded-control border border-border bg-canvas px-3 py-2 text-sm text-foreground"
          >
            {cards.map((card) => (
              <option key={card.id} value={card.id}>
                {card.label || card.type} ····{card.panLast4}
              </option>
            ))}
          </select>
        </label>
      )}

      {/* Multiplier is visibly inert - see this file's ROUNDUP_MULTIPLIERS comment. */}
      <div className="flex gap-2">
        {ROUNDUP_MULTIPLIERS.map((multiplier) => (
          <button
            key={multiplier}
            type="button"
            disabled={multiplier !== 1}
            className={`flex-1 rounded-control px-3 py-1.5 text-xs font-medium disabled:opacity-30 ${
              multiplier === 1 ? 'bg-surface-raised text-foreground' : 'bg-surface-raised text-muted'
            }`}
          >
            {multiplier}×
          </button>
        ))}
      </div>
      <p className="text-xs text-subtle">Round-up multipliers beyond 1× aren't available yet.</p>
    </section>
  )
}

function GoalEditor({ vault }: { vault: VaultResponse }) {
  const update = useUpdateVault(vault.id)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(vault.goalMinor !== null ? formatMoney(vault.goalMinor, vault.currency) : '')

  function save() {
    const goalMinor = draft.trim() === '' ? null : parseMoneyToMinor(draft, vault.currency)
    update.mutate(
      { goalMinor, roundupEnabled: vault.roundupEnabled, roundupSourceCardId: vault.roundupSourceCardId },
      { onSuccess: () => setEditing(false) },
    )
  }

  if (!editing) {
    return (
      <button
        type="button"
        onClick={() => setEditing(true)}
        className="text-xs font-medium text-accent"
      >
        {vault.goalMinor !== null ? 'Edit goal' : 'Set a goal'}
      </button>
    )
  }

  return (
    <div className="flex items-center gap-2">
      <input
        inputMode="decimal"
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        placeholder="No goal"
        className="flex-1 rounded-control border border-border bg-canvas px-2 py-1.5 text-sm text-foreground"
      />
      <button
        type="button"
        disabled={update.isPending}
        onClick={save}
        className="rounded-control bg-accent px-3 py-1.5 text-xs font-medium text-white disabled:opacity-30"
      >
        Save
      </button>
      <button
        type="button"
        onClick={() => setEditing(false)}
        className="rounded-control border border-border px-2 py-1.5 text-xs text-muted"
      >
        Cancel
      </button>
    </div>
  )
}

function VaultCard({ vault, onOpen }: { vault: VaultResponse; onOpen: () => void }) {
  const { accounts } = useAccountsWithBalances()
  const account = accounts.find((entry) => entry.id === vault.accountId)
  const balanceMinor = account?.availableMinor ?? null
  const progress = vault.goalMinor && vault.goalMinor > 0 && balanceMinor !== null
    ? balanceMinor / vault.goalMinor
    : null

  return (
    <button
      type="button"
      onClick={onOpen}
      className="flex w-full items-center gap-4 rounded-card bg-surface p-4 text-left transition-colors hover:bg-surface-raised"
    >
      <ProgressRing progress={progress} />
      <div className="flex flex-1 flex-col">
        <p className="text-sm font-medium text-foreground">{vault.name}</p>
        <p className="text-lg font-semibold text-foreground">
          {balanceMinor !== null ? formatMoney(balanceMinor, vault.currency) : account?.balanceError ? 'Balance unavailable' : '…'}
        </p>
        {vault.goalMinor !== null && (
          <p className="text-xs text-subtle">of {formatMoney(vault.goalMinor, vault.currency)} goal</p>
        )}
        {vault.roundupEnabled && <p className="text-xs text-accent">Round-ups on</p>}
      </div>
    </button>
  )
}

const CURRENCIES = ['EUR', 'GBP', 'USD', 'JPY']

function CreateVaultForm({ onDone }: { onDone: () => void }) {
  const [name, setName] = useState('')
  const [currency, setCurrency] = useState(CURRENCIES[0])
  const [goalInput, setGoalInput] = useState('')
  const create = useCreateVault()
  const apiError = create.error instanceof ApiError ? create.error : null

  function submit() {
    if (name.trim() === '') {
      return
    }
    const goalMinor = goalInput.trim() === '' ? null : parseMoneyToMinor(goalInput, currency)
    create.mutate(
      { name: name.trim(), currency, goalMinor, roundupEnabled: false, roundupSourceCardId: null },
      { onSuccess: onDone },
    )
  }

  return (
    <section className="flex flex-col gap-3 rounded-card bg-surface p-5">
      <h2 className="text-sm font-medium text-muted">New vault</h2>
      <label className="flex flex-col gap-1 text-xs text-muted">
        Name
        <input
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="e.g. Holiday"
          className="rounded-control border border-border bg-canvas px-3 py-2 text-sm text-foreground"
          autoFocus
        />
      </label>
      <label className="flex flex-col gap-1 text-xs text-muted">
        Currency
        <select
          value={currency}
          onChange={(event) => setCurrency(event.target.value)}
          className="rounded-control border border-border bg-canvas px-3 py-2 text-sm text-foreground"
        >
          {CURRENCIES.map((code) => (
            <option key={code} value={code}>
              {code}
            </option>
          ))}
        </select>
      </label>
      <label className="flex flex-col gap-1 text-xs text-muted">
        Goal (optional)
        <input
          inputMode="decimal"
          value={goalInput}
          onChange={(event) => setGoalInput(event.target.value)}
          placeholder="0.00"
          className="rounded-control border border-border bg-canvas px-3 py-2 text-sm text-foreground"
        />
      </label>
      {apiError && <p className="text-xs text-negative">{apiError.detail ?? apiError.message}</p>}
      <div className="flex gap-3">
        <button
          type="button"
          onClick={onDone}
          className="flex-1 rounded-control border border-border px-4 py-2 text-sm font-medium text-muted"
        >
          Cancel
        </button>
        <button
          type="button"
          disabled={name.trim() === '' || create.isPending}
          onClick={submit}
          className="flex-1 rounded-control bg-accent px-4 py-3 text-sm font-medium text-white transition-colors hover:bg-accent-hover disabled:opacity-40"
        >
          {create.isPending ? 'Creating…' : 'Create vault'}
        </button>
      </div>
    </section>
  )
}

function VaultDetail({ vault, onClose }: { vault: VaultResponse; onClose: () => void }) {
  const { accounts } = useAccountsWithBalances()
  const account = accounts.find((entry) => entry.id === vault.accountId)
  const balanceMinor = account?.availableMinor ?? null
  const [transferring, setTransferring] = useState(false)

  return (
    <div className="flex flex-col gap-4 rounded-card bg-surface p-5">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-foreground">{vault.name}</h2>
        <button type="button" onClick={onClose} className="text-xs text-muted">
          Close
        </button>
      </div>

      <div className="flex items-center gap-4">
        <ProgressRing
          progress={
            vault.goalMinor && vault.goalMinor > 0 && balanceMinor !== null ? balanceMinor / vault.goalMinor : null
          }
        />
        <div>
          <p className="text-2xl font-semibold text-foreground">
            {balanceMinor !== null ? formatMoney(balanceMinor, vault.currency) : 'Balance unavailable'}
          </p>
          {vault.goalMinor !== null && (
            <p className="text-xs text-subtle">of {formatMoney(vault.goalMinor, vault.currency)} goal</p>
          )}
        </div>
      </div>

      <GoalEditor vault={vault} />

      <button
        type="button"
        onClick={() => setTransferring(true)}
        className="rounded-control bg-accent px-4 py-3 text-sm font-medium text-white transition-colors hover:bg-accent-hover"
      >
        Move money
      </button>

      <RoundUpSettings vault={vault} />

      {transferring && (
        <TransferModal vault={vault} balanceMinor={balanceMinor} onClose={() => setTransferring(false)} />
      )}
    </div>
  )
}

export function VaultsScreen() {
  const { data: vaults, isLoading, isError } = useVaults()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  const selected = vaults?.find((vault) => vault.id === selectedId) ?? null

  return (
    <div className="flex flex-col gap-5 px-5 pb-6 pt-8">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold text-foreground">Vaults</h1>
        {!creating && !selected && vaults && vaults.length > 0 && (
          <button type="button" onClick={() => setCreating(true)} className="text-sm font-medium text-accent">
            + New
          </button>
        )}
      </div>

      {isLoading ? (
        <p className="text-sm text-subtle">Loading…</p>
      ) : isError ? (
        <p className="text-sm text-negative">Could not load your vaults.</p>
      ) : selected ? (
        <VaultDetail vault={selected} onClose={() => setSelectedId(null)} />
      ) : creating ? (
        <CreateVaultForm onDone={() => setCreating(false)} />
      ) : vaults && vaults.length > 0 ? (
        <div className="flex flex-col gap-3">
          {vaults.map((vault) => (
            <VaultCard key={vault.id} vault={vault} onOpen={() => setSelectedId(vault.id)} />
          ))}
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          <p className="text-sm text-muted">
            You don't have a vault yet. Create one to set money aside toward a goal.
          </p>
          <CreateVaultForm onDone={() => setCreating(false)} />
        </div>
      )}
    </div>
  )
}
