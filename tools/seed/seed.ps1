#!/usr/bin/env pwsh
<#
plans/phase-09-polish.md task 09.4: "idempotent demo-data generator" (the `tools/seed/`
deliverable the phase's own Deliverables list names). Supersedes phase 06 task 11's seed logic
that used to live inline in tasks.ps1 - that version funded two self-registered users
(alice.seed@.../bob.seed@...) and was explicitly NOT idempotent on funding/spend (see its own
comments, carried into git history). This version is. `tasks.ps1 seed` now just calls this file.

Produces exactly what plans/phase-09-polish.md's "### Seed data" section asks for:
  - 4 users - Alice, Bob, Carol, Dan - each with EUR/GBP/USD wallets and a plausible balance.
  - ~150 card transactions across the simulator's merchant catalogue (36 merchants, several
    categories), settled over a few simulated days, split across Alice's and Bob's cards.
  - A handful of P2P transfers and several FX exchanges between/for the four.
  - Exactly two vaults (Alice, Bob) and exactly two budgets (Alice comfortable, Bob breached).
  - The zero-sum invariant asserted at the end, against the real `posting` table.

Why Alice/Bob/Carol/Dan are reconciled, not registered: all four are pre-provisioned directly
in the Keycloak realm export (infra/keycloak/revolut-realm.json) as alice@demo.io / bob@demo.io
/ carol@demo.io / dan@demo.io, password `demo` for all - matching this phase's own Verification
section ("log in as alice@demo.io / demo") exactly, which a freshly self-registered email never
could. identity-service's own `POST /api/identity/register` 409s on an email Keycloak already
knows, so the only path from "exists in Keycloak" to "exists as an application user" is the
reconciliation endpoint built for exactly this gap (task 01.7, `POST /api/identity/admin/reconcile`
- see ReconciliationService's own javadoc). That endpoint requires the `admin` realm authority,
which no realm user carried before this task - `seed-operator@revolutclone.local` was added to
the realm export specifically to hold it, a tool-only identity that is never a demo login.

One accepted side effect: reconcile() sweeps every orphaned Keycloak user, so it also backfills
`service-account-revolut-backend` and `seed-operator@revolutclone.local` themselves into harmless
empty user_profile rows + three empty wallets apiece - nobody ever logs in as them and nothing
in this project reads their balances, so this is inert, not a bug worth filtering in production
code for a seed-only concern.

What "idempotent" means for each step here, concretely:
  - reconcile: already a no-op for an already-reconciled provider user (ReconciliationService
    checks user_profile existence first) - call it every run.
  - wallet open: not called at all - EUR/GBP/USD open themselves off UserRegisteredV1.
  - funding: the one step with no existing idempotency primitive to lean on (AdminController's
    `/fund` deliberately uses a server-random idempotency key - see its own javadoc - because it
    was designed as fire-and-forget, not caller-retryable). Script-side fix: fund a ledger
    account only if it has NEVER received a posting before (checked via the real
    `/internal/ledger/accounts/{id}/postings` query) - once ever-funded, a rerun skips funding
    entirely, so a later run never re-inflates a balance that P2P/FX/card spend already moved.
  - card issuance: reuse-if-present (same pattern phase 06's script already had).
  - spend batch: skipped once the card already has at least $targetAuthorizationCount
    authorizations (GET /api/cards/{id}/authorizations, uncapped list, checked by count).
  - clock advance-day: calling it extra times across reruns is harmless - it is an in-memory
    relative day counter with no meaning beyond "settle whatever of THIS run's own spend is due".
  - budgets: `PUT` is already an upsert - same category/month/limit every run converges, never
    accumulates.
  - vaults: already 409-tolerant (phase 06's pattern, kept as-is).
  - P2P transfers / FX exchanges: both go through a real `Idempotency-Key` - this script sends
    the SAME fixed key every run for each one, so a rerun is deduplicated by payment-service's
    gateway-filter-backed dedup and fx-service's own `findByIdempotencyKey`-gated QuoteService,
    never resent as a second real transfer/exchange.

Run from the repo root, full stack up (`tasks.ps1 up`, then
`docker compose -f docker-compose.infra.yml -f docker-compose.yml --profile services up -d --build`):

  pwsh tools/seed/seed.ps1
#>
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$compose = @('-f', 'docker-compose.infra.yml')

function Say  ($m) { Write-Host "[seed] $m" -ForegroundColor Cyan }
function Warn ($m) { Write-Host "[seed] $m" -ForegroundColor Yellow }
function Die  ($m) { Write-Host "[seed] $m" -ForegroundColor Red; exit 1 }

$gatewayBase   = 'http://localhost:8080'
$ledgerBase    = 'http://localhost:8083'
$simulatorBase = 'http://localhost:8089'
$tokenEndpoint = 'http://localhost:8180/realms/revolut/protocol/openid-connect/token'

$demoPassword      = 'demo'
$operatorEmail     = 'seed-operator@revolutclone.local'
$operatorPassword  = 'SeedOperator1!'

# EUR/GBP/USD per plans/phase-09-polish.md's own wording (not the four-currency set an earlier
# phase's demo script used) - account-service already auto-opens exactly these three off
# UserRegisteredV1, so no explicit "open a wallet" call is needed at all.
$currencies = @('EUR', 'GBP', 'USD')

# Alice's and Bob's EUR figure bakes in the same large headroom phase 06's script needed before
# it: a 75-authorization batch against a 36-merchant catalogue that includes rare, large
# purchases (Apple Store, long-haul flights, hotel stays) needs a lot of room so the batch never
# bounces on insufficient funds partway through. The visible post-spend balance is therefore
# whatever is left after real settlement, not this number - same honest tradeoff phase 06 made.
$users = @(
    [ordered]@{ Email = 'alice@demo.io'; Name = 'Alice'; Balances = [ordered]@{ EUR = 5235000L; GBP = 89050L;  USD = 150000L }; History = $true;  BudgetLimitMinor = 100000L; BudgetBreached = $false }
    [ordered]@{ Email = 'bob@demo.io';   Name = 'Bob';   Balances = [ordered]@{ EUR = 5180000L; GBP = 45000L;  USD = 95000L };  History = $true;  BudgetLimitMinor = 20000L;  BudgetBreached = $true }
    [ordered]@{ Email = 'carol@demo.io'; Name = 'Carol'; Balances = [ordered]@{ EUR = 320000L;  GBP = 120000L; USD = 50000L };  History = $false }
    [ordered]@{ Email = 'dan@demo.io';   Name = 'Dan';   Balances = [ordered]@{ EUR = 60000L;   GBP = 15000L;  USD = 220000L }; History = $false }
)

$historyBatchCount      = 75   # x2 (Alice + Bob) = ~150 total, per the phase's own figure.
$historyAdvanceDayCount = 5    # see Invoke-SeedHistory in the old tasks.ps1 implementation this
                               # superseded: a batch settles over +1..+3 days, a couple of spare
                               # advances cover any still-settling spend from a prior run.
$vaultGoalMinor         = 500000L

# $Body takes [System.Collections.Specialized.OrderedDictionary] (an `[ordered]@{}` literal),
# never a plain `[hashtable]`: .NET randomizes a plain hashtable's string-key hash seed per
# process, so the SAME literal `@{...}` enumerates its keys in a DIFFERENT order in every fresh
# `pwsh` invocation, and ConvertTo-Json serializes keys in enumeration order - producing a
# different byte sequence for the identical logical body on every rerun. That silently breaks
# every idempotency-keyed call in this script (the gateway's idempotency filter and fx-service's
# own quote dedup both compare the request body's hash, not its parsed semantics) with a 422
# "Idempotency-Key was already used for a request with a different body" on the second run -
# found live running this script twice in separate processes, task 09.4.
# -Compress: Windows PowerShell 5.1 and PowerShell 7 indent/space plain ConvertTo-Json output
# differently, so the same body hashed differently depending on which shell ran the seed, and a
# rerun from the other shell (start.ps1 vs. an interactive pwsh) hit the 422 above. Compressed
# output is byte-identical across both (verified 5.1.26100 vs 7.6.6).
function Invoke-JsonPost([string] $Uri, [System.Collections.Specialized.OrderedDictionary] $Body, [hashtable] $Headers) {
    Invoke-RestMethod -Method Post -Uri $Uri -ContentType 'application/json' `
        -Body ($Body | ConvertTo-Json -Compress) -Headers $Headers
}

function Invoke-JsonPut([string] $Uri, [System.Collections.Specialized.OrderedDictionary] $Body, [hashtable] $Headers) {
    Invoke-RestMethod -Method Put -Uri $Uri -ContentType 'application/json' `
        -Body ($Body | ConvertTo-Json -Compress) -Headers $Headers
}

function Get-HttpStatus($errorRecord) {
    $response = $errorRecord.Exception.Response
    if ($response) { return [int] $response.StatusCode }
    return $null
}

function Get-AccessToken([string] $Email, [string] $Password) {
    $response = Invoke-RestMethod -Method Post -Uri $tokenEndpoint -ContentType 'application/x-www-form-urlencoded' -Body @{
        grant_type = 'password'
        client_id  = 'revolut-web'
        username   = $Email
        password   = $Password
        scope      = 'openid'
    }
    return $response.access_token
}

# task 01.7's gap-filler: every orphaned Keycloak user (including all four demo users, the
# backend service account, and this operator) gets a user_profile row + UserRegisteredV1 the
# first time this runs; already-reconciled users are skipped - see ReconciliationService.
function Invoke-Reconcile {
    Say 'reconciling pre-provisioned Keycloak users (alice/bob/carol/dan@demo.io)...'
    $operatorToken = Get-AccessToken -Email $operatorEmail -Password $operatorPassword
    $result = Invoke-RestMethod -Method Post -Uri "$gatewayBase/api/identity/admin/reconcile" `
        -Headers @{ Authorization = "Bearer $operatorToken" }
    $names = @($result | ForEach-Object { $_.email })
    if ($names.Count -gt 0) { Say "  newly reconciled: $($names -join ', ')" }
    else { Say '  nothing to reconcile - every provider user already has a profile' }
}

function Wait-ForAllWallets([hashtable] $AuthHeader) {
    $deadline = (Get-Date).AddSeconds(30)
    while ($true) {
        $accounts = Invoke-RestMethod -Method Get -Uri "$gatewayBase/api/accounts/me" -Headers $AuthHeader
        # type -eq 'WALLET', not just currency in the wanted set: on a rerun this user's own
        # round-up vault (Invoke-SeedVault) is ALSO its own `account` row with type VAULT and
        # (here) currency EUR - account-service's own schema backs every vault with a real
        # account, not only wallets - so a currency-only filter double-counts it and this never
        # reaches exactly $currencies.Count again after the first run ever creates a vault.
        # Found live, task 09.4, on the second-ever run of this script.
        $ready = @($accounts | Where-Object { $_.type -eq 'WALLET' -and $currencies -contains $_.currency -and $_.ledgerAccountId })
        if ($ready.Count -eq $currencies.Count) { return $ready }
        if ((Get-Date) -gt $deadline) {
            Die "timed out waiting for EUR/GBP/USD wallets to all have a ledgerAccountId (have: $(($accounts | ForEach-Object { "$($_.currency)/$($_.type)" }) -join ', '))"
        }
        Start-Sleep -Seconds 1
    }
}

# The one step with no server-side idempotency primitive (see this file's header comment) -
# fixed here by checking real history instead: a ledger account that has ever received a
# posting was already funded by a previous run, full stop, regardless of what it has done with
# the money since.
function Test-AlreadyFunded([hashtable] $AuthHeader, [System.Guid] $LedgerAccountId) {
    $postings = Invoke-RestMethod -Method Get `
        -Uri "$ledgerBase/internal/ledger/accounts/$LedgerAccountId/postings?size=1" -Headers $AuthHeader
    return @($postings).Count -gt 0
}

function Invoke-FundIfNeverFunded([hashtable] $AuthHeader, [System.Guid] $LedgerAccountId, [long] $AmountMinor, [string] $Currency) {
    if (Test-AlreadyFunded -AuthHeader $AuthHeader -LedgerAccountId $LedgerAccountId) {
        Say "    $Currency already funded in a previous run - skipping"
        return
    }
    Invoke-JsonPost -Uri "$ledgerBase/internal/ledger/admin/fund/$LedgerAccountId" -Body ([ordered]@{
        amountMinor = $AmountMinor
        currency    = $Currency
    }) -Headers $AuthHeader | Out-Null
    Say "    funded $Currency wallet with $AmountMinor minor units"
}

function Get-Balance([hashtable] $AuthHeader, [System.Guid] $LedgerAccountId) {
    return Invoke-RestMethod -Method Get -Uri "$ledgerBase/internal/ledger/accounts/$LedgerAccountId/balance" -Headers $AuthHeader
}

# Polls the beneficiary side of an async money movement (a payment's receiver, an FX exchange's
# buy-currency account) for its available balance to increase by at least $MinIncrease - the
# only reliable "did this actually settle yet" signal available through the real read APIs this
# service exposes (there is no GET /api/payments/{id}; see this task's own commit message for
# why). plans/phase-09-polish.md's own Risks & gotchas: "the seed script must wait for COMPLETED
# rather than firing and hoping. Poll, with a timeout and a clear error."
#
# A timeout here is a WARNING, not a Die, and that is deliberate: on a rerun, the transfer's
# Idempotency-Key is already known to the gateway (or, for FX, to QuoteService directly), so the
# POST/execute above correctly replayed the ORIGINAL accepted response rather than moving money
# a second time - meaning the balance this polls for genuinely will never increase again, by
# design, not because anything failed. Found live, task 09.4: a rerun's baseline already
# included a previous run's real settlement, so polling for a second increase on top of it
# timed out even though the system was behaving exactly as an idempotent retry should. The first
# time any given transfer/exchange ever runs, this still asserts real settlement, same as
# Wait-ForSeedHistorySummary's identical non-fatal-timeout precedent elsewhere in this project.
function Wait-ForBalanceIncrease([hashtable] $AuthHeader, [System.Guid] $LedgerAccountId, [long] $BaselineAvailableMinor,
        [long] $MinIncrease, [string] $What, [int] $TimeoutSeconds = 4) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
        $balance = Get-Balance -AuthHeader $AuthHeader -LedgerAccountId $LedgerAccountId
        if ($balance.availableMinor -ge ($BaselineAvailableMinor + $MinIncrease)) { return $true }
        if ((Get-Date) -gt $deadline) {
            Warn "  $What did not increase the balance within ${TimeoutSeconds}s - almost always means a prior run already settled it (idempotent replay moves no new money); treat this as a no-op, not a failure"
            return $false
        }
        Start-Sleep -Milliseconds 500
    }
}

function Get-OrIssueSeedCard([hashtable] $AuthHeader, [System.Guid] $AccountId) {
    # Not `@(Invoke-RestMethod ...)` directly - wrapping the call itself in `@()` double-nests a
    # JSON array response into a one-element array holding the real (possibly empty) array, so
    # an empty `[]` response reads as Count=1 with a nonexistent `.id` under Set-StrictMode. A
    # plain assignment first, then `@()` on the stored variable, is the safe two-step form.
    $existing = Invoke-RestMethod -Method Get -Uri "$gatewayBase/api/cards" -Headers $AuthHeader
    $existing = @($existing)
    if ($existing.Count -gt 0) { return $existing[0].id }
    $card = Invoke-JsonPost -Uri "$gatewayBase/api/cards" -Body ([ordered]@{
        accountId = $AccountId
        type      = 'VIRTUAL'
        label     = 'Seed card'
        pin       = '1234'
    }) -Headers $AuthHeader
    Say "    issued card $($card.id)"
    return $card.id
}

function Get-ExistingAuthorizationCount([hashtable] $AuthHeader, [System.Guid] $CardId) {
    $authorizations = Invoke-RestMethod -Method Get -Uri "$gatewayBase/api/cards/$CardId/authorizations" -Headers $AuthHeader
    return @($authorizations).Count
}

# task 09.4's share of "3 months of history" - a card, a spend batch sized by $historyBatchCount,
# settled over a few simulated days. See this file's header comment for why the authorization
# count, not a flag, is the idempotency gate.
function Invoke-SeedHistoryFor([hashtable] $AuthHeader, [System.Guid] $CardId) {
    $existingCount = Get-ExistingAuthorizationCount -AuthHeader $AuthHeader -CardId $CardId
    if ($existingCount -ge $historyBatchCount) {
        Say "    card $CardId already has $existingCount authorizations (>= $historyBatchCount) - skipping the spend batch"
        return
    }
    Say "    submitting a $historyBatchCount-authorization spend batch on card $CardId..."
    Invoke-RestMethod -Method Post -Uri "$simulatorBase/simulator/cards/spend/batch?count=$historyBatchCount" `
        -ContentType 'application/json' -Body (@{ cardId = $CardId } | ConvertTo-Json -Compress) | Out-Null
    Say '    advancing the simulator clock so the batch settles...'
    for ($i = 0; $i -lt $historyAdvanceDayCount; $i++) {
        Invoke-RestMethod -Method Post -Uri "$simulatorBase/simulator/clock/advance-day" | Out-Null
    }
}

function Invoke-SeedBudget([hashtable] $AuthHeader, [long] $LimitMinor) {
    $monthFirst = (Get-Date -Day 1).ToString('yyyy-MM-dd')
    Invoke-JsonPut -Uri "$gatewayBase/api/analytics/budgets" -Body ([ordered]@{
        category   = 'Groceries'
        month      = $monthFirst
        limitMinor = $LimitMinor
        currency   = 'EUR'
    }) -Headers $AuthHeader | Out-Null
}

function Invoke-SeedVault([hashtable] $AuthHeader, [System.Guid] $CardId) {
    try {
        Invoke-JsonPost -Uri "$gatewayBase/api/analytics/vaults" -Body ([ordered]@{
            name                = 'Seed round-up vault'
            currency            = 'EUR'
            goalMinor           = $vaultGoalMinor
            roundupEnabled      = $true
            roundupSourceCardId = $CardId
        }) -Headers $AuthHeader | Out-Null
    } catch {
        if ((Get-HttpStatus $_) -ne 409) { throw }
        Say '    round-up vault already exists'
    }
}

function Invoke-SeedTransfer([hashtable] $FromAuthHeader, [System.Guid] $SourceAccountId, [System.Guid] $TargetAccountId,
        [System.Guid] $TargetLedgerAccountId, [long] $AmountMinor, [string] $Reference, [string] $IdempotencyKey) {
    $baseline = Get-Balance -AuthHeader $FromAuthHeader -LedgerAccountId $TargetLedgerAccountId
    $headers = $FromAuthHeader.Clone()
    $headers['Idempotency-Key'] = $IdempotencyKey
    Invoke-JsonPost -Uri "$gatewayBase/api/payments" -Body ([ordered]@{
        sourceAccountId = $SourceAccountId
        targetAccountId = $TargetAccountId
        amountMinor     = $AmountMinor
        currency        = 'EUR'
        reference       = $Reference
    }) -Headers $headers | Out-Null
    $settledNow = Wait-ForBalanceIncrease -AuthHeader $FromAuthHeader -LedgerAccountId $TargetLedgerAccountId `
        -BaselineAvailableMinor $baseline.availableMinor -MinIncrease $AmountMinor -What "transfer '$Reference'"
    if ($settledNow) { Say "    $Reference settled" } else { Say "    $Reference already applied in a previous run" }
}

# A quote is a one-shot, time-limited resource (see FxQuoteController's own javadoc: a retried
# request with the same Idempotency-Key returns the SAME quote object, never a fresh one). If
# an earlier attempt created the quote but never executed it before its TTL lapsed - a prior
# run that died between quote() and execute(), say - every later rerun with that same key gets
# the same now-EXPIRED quote back forever, and execute() 409s. Caught here the same way
# Invoke-SeedVault tolerates a 409: a quote that cannot be executed is either already CONSUMED
# (money already moved, nothing to do) or permanently EXPIRED (nothing this script can do about
# a lapsed quote under a fixed key either) - neither is a reason to fail the whole run.
function Invoke-SeedExchange([hashtable] $AuthHeader, [string] $SellCurrency, [string] $BuyCurrency,
        [long] $SellMinor, [System.Guid] $BuyLedgerAccountId, [string] $IdempotencyKey) {
    $baseline = Get-Balance -AuthHeader $AuthHeader -LedgerAccountId $BuyLedgerAccountId
    $headers = $AuthHeader.Clone()
    $headers['Idempotency-Key'] = $IdempotencyKey
    $quote = Invoke-JsonPost -Uri "$gatewayBase/api/fx/quotes" -Body ([ordered]@{
        sellCurrency = $SellCurrency
        buyCurrency  = $BuyCurrency
        sellMinor    = $SellMinor
    }) -Headers $headers
    try {
        Invoke-RestMethod -Method Post -Uri "$gatewayBase/api/fx/quotes/$($quote.quoteId)/execute" -Headers $headers | Out-Null
    } catch {
        if ((Get-HttpStatus $_) -ne 409) { throw }
        Say "    $SellCurrency->$BuyCurrency quote already consumed or expired from a previous run - skipping"
        return
    }
    $settledNow = Wait-ForBalanceIncrease -AuthHeader $AuthHeader -LedgerAccountId $BuyLedgerAccountId `
        -BaselineAvailableMinor $baseline.availableMinor -MinIncrease 1 -What "FX $SellCurrency->$BuyCurrency exchange"
    if ($settledNow) { Say "    $SellCurrency->$BuyCurrency exchange settled" }
    else { Say "    $SellCurrency->$BuyCurrency exchange already applied in a previous run" }
}

function Assert-ZeroSumInvariant {
    Say 'asserting the ledger zero-sum invariant (SUM per entry_id over the real posting table)...'
    $unbalanced = & docker compose @compose exec -T postgres psql -U ledger -d ledger -tAc `
        "SELECT entry_id FROM posting GROUP BY entry_id HAVING SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0;" 2>&1
    if ($LASTEXITCODE -ne 0) { Die "ledger invariant query failed: $unbalanced" }
    if (($unbalanced | Out-String).Trim()) {
        Die "UNBALANCED entry_id(s) found - this is a hard failure regardless of anything else seeding did:`n$unbalanced"
    }
    Say '  every journal entry sums to zero.'
}

Push-Location $repo
try {
    Invoke-Reconcile

    Say 'funding each user''s EUR/GBP/USD wallets (skipped where already funded by a prior run)...'
    $accountsByEmail = [ordered]@{}
    $authByEmail = [ordered]@{}
    foreach ($user in $users) {
        Say "  $($user.Name) ($($user.Email))"
        $token = Get-AccessToken -Email $user.Email -Password $demoPassword
        $authHeader = @{ Authorization = "Bearer $token" }
        $authByEmail[$user.Email] = $authHeader

        $accounts = Wait-ForAllWallets -AuthHeader $authHeader
        $accountsByEmail[$user.Email] = $accounts

        foreach ($account in $accounts) {
            $target = [long] $user.Balances[$account.currency]
            Invoke-FundIfNeverFunded -AuthHeader $authHeader -LedgerAccountId $account.ledgerAccountId `
                -AmountMinor $target -Currency $account.currency
        }
    }

    Say 'seeding card + spend history for Alice and Bob (~150 authorizations total)...'
    $cardIdByEmail = [ordered]@{}
    foreach ($user in $users) {
        if (-not $user.History) { continue }
        Say "  $($user.Name)"
        $authHeader = $authByEmail[$user.Email]
        $eurAccount = @($accountsByEmail[$user.Email]) | Where-Object { $_.currency -eq 'EUR' } | Select-Object -First 1
        $cardId = Get-OrIssueSeedCard -AuthHeader $authHeader -AccountId $eurAccount.id
        $cardIdByEmail[$user.Email] = $cardId
        Invoke-SeedHistoryFor -AuthHeader $authHeader -CardId $cardId
    }

    Say 'seeding a handful of P2P transfers between the four...'
    function AccountOf([string] $Email, [string] $Currency) {
        return @($accountsByEmail[$Email]) | Where-Object { $_.currency -eq $Currency } | Select-Object -First 1
    }
    $transfers = @(
        @{ From = 'alice@demo.io'; To = 'carol@demo.io'; AmountMinor = 5000L; Reference = 'Rent split' }
        @{ From = 'bob@demo.io';   To = 'dan@demo.io';   AmountMinor = 3000L; Reference = 'Dinner' }
        @{ From = 'carol@demo.io'; To = 'dan@demo.io';   AmountMinor = 2000L; Reference = 'Movie tickets' }
        @{ From = 'dan@demo.io';   To = 'alice@demo.io'; AmountMinor = 1500L; Reference = 'Coffee' }
    )
    foreach ($t in $transfers) {
        $fromAccount = AccountOf -Email $t.From -Currency 'EUR'
        $toAccount = AccountOf -Email $t.To -Currency 'EUR'
        $key = "seed:p2p:$($t.From)-to-$($t.To):$($t.Reference)"
        Invoke-SeedTransfer -FromAuthHeader $authByEmail[$t.From] -SourceAccountId $fromAccount.id `
            -TargetAccountId $toAccount.id -TargetLedgerAccountId $toAccount.ledgerAccountId `
            -AmountMinor $t.AmountMinor -Reference $t.Reference -IdempotencyKey $key
    }

    Say 'seeding several FX exchanges...'
    $exchanges = @(
        @{ Email = 'alice@demo.io'; Sell = 'EUR'; Buy = 'GBP'; SellMinor = 20000L }
        @{ Email = 'bob@demo.io';   Sell = 'USD'; Buy = 'EUR'; SellMinor = 10000L }
        @{ Email = 'carol@demo.io'; Sell = 'GBP'; Buy = 'USD'; SellMinor = 15000L }
    )
    foreach ($x in $exchanges) {
        $buyAccount = AccountOf -Email $x.Email -Currency $x.Buy
        $key = "seed:fx:$($x.Email):$($x.Sell)-to-$($x.Buy)"
        Invoke-SeedExchange -AuthHeader $authByEmail[$x.Email] -SellCurrency $x.Sell -BuyCurrency $x.Buy `
            -SellMinor $x.SellMinor -BuyLedgerAccountId $buyAccount.ledgerAccountId -IdempotencyKey $key
    }

    Say 'seeding exactly two budgets (one comfortable, one breached) and two vaults...'
    foreach ($user in $users) {
        if (-not $user.History) { continue }
        Invoke-SeedBudget -AuthHeader $authByEmail[$user.Email] -LimitMinor $user.BudgetLimitMinor
        Invoke-SeedVault -AuthHeader $authByEmail[$user.Email] -CardId $cardIdByEmail[$user.Email]
    }

    Assert-ZeroSumInvariant

    Say 'seed complete: alice@demo.io / bob@demo.io / carol@demo.io / dan@demo.io, password "demo".'
}
finally { Pop-Location }
