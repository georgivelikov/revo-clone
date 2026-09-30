#!/usr/bin/env pwsh
<#
Wrapper around docker-compose.infra.yml so the phase-00 Verification block's commands
(`.\tasks.ps1 up`, `.\tasks.ps1 topics`, `.\tasks.ps1 topics --describe`) actually exist.
See plans/phase-00-foundations.md §Verification and §Risks & gotchas.

  tasks.ps1 up                up the infra stack, wait for every container healthy
  tasks.ps1 down               stop the stack, keep volumes
  tasks.ps1 reset              stop the stack AND drop its volumes (fresh Postgres/Kafka/
                                Keycloak data) -- the only way Keycloak re-imports the realm,
                                since --import-realm only fires against an empty database
  tasks.ps1 logs <svc>         tail a service's logs (--follow / -f to stream)
  tasks.ps1 psql <db>          open psql in the postgres container as that database's owner
  tasks.ps1 topics             create every topic in the catalogue (idempotent)
  tasks.ps1 topics --describe  show cleanup.policy/retention.ms for every declared topic
  tasks.ps1 seed                two demo users (Alice, Bob), each with EUR/GBP/USD/JPY wallets
                                 funded - plans/phase-04-fx-multicurrency.md task 11. Needs the
                                 full stack up, not just infra: `docker compose --profile
                                 services up -d --build` alongside `tasks.ps1 up`.
  tasks.ps1 seed --with-history  everything the plain `seed` above does, plus for each demo
                                 user: a EUR-linked card, a ~100-authorization card-network
                                 spend batch settled via the simulator's controllable clock, a
                                 Groceries budget sized to cross its own 80%/100% alerts, and a
                                 round-up vault sourced from that card - plans/phase-06-vaults-
                                 analytics.md task 11, so the Analytics dashboard and Vaults
                                 screen are not empty. Needs simulator-service and
                                 analytics-service up too (both are already in the `services`
                                 compose profile). See Invoke-SeedHistory's own comment for why
                                 this is real transaction *volume*, landing in whatever the real
                                 current month is, rather than literal 3-calendar-month-old
                                 backdated timestamps - that is not something this system's
                                 Clock-injected outbox/event pipeline can produce.
  tasks.ps1 analytics-rebuild   plans/phase-07-resilience-and-scale.md's ### Replay, flavour 1:
                                 stops analytics-service (a consumer-group offset reset silently
                                 no-ops against a running member), drops and recreates its schema,
                                 resets its Kafka consumer group to offset zero on both topics it
                                 reads, then restarts it - Flyway re-migrates and the consumers
                                 replay ledger.entry.v1/card.authorization.v1 from the start. The
                                 dashboard comes back identical and nothing re-notifies. Needs the
                                 full stack up (`docker compose -f docker-compose.infra.yml -f
                                 docker-compose.yml --profile services up -d --build`), same as
                                 --with-history above.
  tasks.ps1 point-in-time <ts>  ### Replay, flavour 2: replays ledger.entry.v1 up to an ISO-8601
                                 instant (e.g. 2026-09-23T14:00:00Z) into ledger-service's
                                 point_in_time_snapshot table and prints every account's balance as
                                 of that moment - "what was every balance last Tuesday at 14:00?",
                                 the thing a mutable-balance design cannot answer. Read-only: a
                                 throwaway consumer group, never the real one. Needs a registered
                                 seed user (`tasks.ps1 seed`) for its bearer token, same as the
                                 funding calls `seed` itself makes against ledger-service.
#>
param(
    [Parameter(Position = 0, Mandatory = $true)]
    [ValidateSet('up', 'down', 'reset', 'logs', 'psql', 'topics', 'seed', 'analytics-rebuild', 'point-in-time')]
    [string] $Command,

    [Parameter(Position = 1)]
    [string] $Target,

    # Catches --describe / --follow / -f as written in plans/phase-00-foundations.md, since
    # PowerShell does not bind double-dash flags to named switch parameters on its own.
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Rest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo    = $PSScriptRoot
$compose = @('-f', 'docker-compose.infra.yml')
# analytics-rebuild needs to stop/start analytics-service itself, which only docker-compose.yml's
# `services` profile defines - see that file's own header comment on why both -f flags have to be
# passed together (one compose project, one shared network) rather than as two invocations.
$composeServices = @('-f', 'docker-compose.infra.yml', '-f', 'docker-compose.yml', '--profile', 'services')

$Describe    = $Rest -contains '--describe'
$Follow      = ($Rest -contains '--follow') -or ($Rest -contains '-f')
$WithHistory = $Rest -contains '--with-history'

$knownDbs = @('identity', 'account', 'ledger', 'payment', 'fx', 'card', 'analytics', 'notification')

function Say  ($m) { Write-Host "[tasks] $m" -ForegroundColor Cyan }
function Warn ($m) { Write-Host "[tasks] $m" -ForegroundColor Yellow }
function Die  ($m) { Write-Host "[tasks] $m" -ForegroundColor Red; exit 1 }

# The topic catalogue lives in one place -- infra/kafka/create-topics.sh -- so `topics
# --describe` reads the names out of it rather than keeping a second copy here to drift.
function Get-TopicNames {
    $script = Join-Path $repo 'infra/kafka/create-topics.sh'
    (Get-Content -LiteralPath $script) |
        Where-Object { $_ -match '^\s*create\s+(\S+)' } |
        ForEach-Object { ($_ -replace '^\s*create\s+', '') -split '\s+' | Select-Object -First 1 }
}

# `seed`'s targets: the gateway for every public route (identity, accounts), Keycloak's own
# token endpoint for a password-grant login (the same public "revolut-web" client the SPA
# uses), and ledger-service directly for its funding admin endpoint, which is deliberately not
# gateway-routed (plans/phase-02-ledger.md's ### API section) - see CLAUDE.md's port map.
$gatewayBase    = 'http://localhost:8080'
$ledgerBase     = 'http://localhost:8083'
# Called directly, never through the gateway - simulator-service is not a JWT resource server
# (no common-security dependency at all; see its pom.xml), the same reasoning that already
# justifies $ledgerBase's direct-to-service call above for an endpoint the gateway never routes.
$simulatorBase  = 'http://localhost:8089'
$tokenEndpoint  = 'http://localhost:8180/realms/revolut/protocol/openid-connect/token'
$seedCurrencies = @('EUR', 'GBP', 'USD', 'JPY')
# One round number for every currency: JPY has zero minor digits, so 50000 minor units is
# ¥50,000 - the same order of magnitude as 500.00 of the other three, not a currency
# conversion. The phase names no specific amount, only "GBP/USD/JPY balances".
$seedAmountMinor = 50000

# --with-history (task 06.11) constants. See Invoke-SeedHistory's own comment for the reasoning
# behind each number.
$historyBatchCount        = 100
$historyAdvanceDayCount   = 5
$historyEurTopUpMinor     = 5000000   # EUR 50,000 - headroom for a 100-authorization batch
$historyBudgetCategory    = 'Groceries'
$historyBudgetLimitMinor  = 20000     # EUR 200 - see Invoke-SeedHistory for why this clears easily
$historyVaultGoalMinor    = 500000    # EUR 5,000, an arbitrary but plausible savings goal

function Invoke-JsonPost([string] $Uri, [hashtable] $Body, [hashtable] $Headers) {
    Invoke-RestMethod -Method Post -Uri $Uri -ContentType 'application/json' `
        -Body ($Body | ConvertTo-Json) -Headers $Headers
}

function Get-HttpStatus($errorRecord) {
    $response = $errorRecord.Exception.Response
    if ($response) { return [int] $response.StatusCode }
    return $null
}

# Registration is the one unauthenticated identity-service route and the only thing that
# triggers account-service's own EUR/GBP/USD auto-open (UserRegisteredV1) - see
# plans/phase-01-identity-account-gateway.md. A 409 here means a previous `seed` run already
# created this exact user: fine, that is what makes running `seed` twice safe to attempt at
# all, rather than a fresh instruction to move money each time (funding itself, further down,
# is NOT idempotent this way yet - phase 09's `tools/seed/` owns making the whole thing so).
function Register-SeedUser([string] $Email, [string] $Password, [string] $FirstName, [string] $LastName) {
    try {
        Invoke-JsonPost -Uri "$gatewayBase/api/identity/register" -Body @{
            email     = $Email
            password  = $Password
            firstName = $FirstName
            lastName  = $LastName
            country   = 'IE'
        } | Out-Null
        Say "  registered $Email"
    } catch {
        if ((Get-HttpStatus $_) -eq 409) {
            Say "  $Email already registered"
        } else {
            Die "registering $Email failed: $($_.Exception.Message)"
        }
    }
}

function Get-SeedAccessToken([string] $Email, [string] $Password) {
    $response = Invoke-RestMethod -Method Post -Uri $tokenEndpoint -ContentType 'application/x-www-form-urlencoded' -Body @{
        grant_type = 'password'
        client_id  = 'revolut-web'
        username   = $Email
        password   = $Password
        scope      = 'openid'
    }
    return $response.access_token
}

# Opens whichever of the four currencies this user does not already have a wallet in. Tried
# for all four, not only JPY: EUR/GBP/USD open themselves asynchronously off the registration
# saga, so calling this again for them is also how the script waits that out, rather than
# guessing a delay - a 409 (DuplicateWalletException) simply means it already exists.
function Open-MissingWallets([hashtable] $AuthHeader) {
    foreach ($currency in $seedCurrencies) {
        try {
            Invoke-JsonPost -Uri "$gatewayBase/api/accounts" -Body @{ currency = $currency } -Headers $AuthHeader | Out-Null
        } catch {
            if ((Get-HttpStatus $_) -ne 409) {
                Die "opening a $currency wallet failed: $($_.Exception.Message)"
            }
        }
    }
}

# Every account-service wallet needs its own ledger_account_id, filled in asynchronously once
# ledger-service consumes AccountOpenedV1 and account-service applies LedgerAccountCreatedV1
# back (plans/phase-01-identity-account-gateway.md) - polled rather than assumed present the
# instant a wallet is opened.
function Wait-ForAllWallets([hashtable] $AuthHeader) {
    $deadline = (Get-Date).AddSeconds(30)
    while ($true) {
        # Deliberately not `@(Invoke-RestMethod ...)`: for a JSON array body, wrapping the call
        # itself in `@()` double-nests the result into a one-element array holding the real
        # array, which silently breaks every property/count check below it. A plain assignment
        # already collects a multi-object pipeline result into a proper array on its own.
        $accounts = Invoke-RestMethod -Method Get -Uri "$gatewayBase/api/accounts/me" -Headers $AuthHeader
        $ready = @($accounts | Where-Object { $seedCurrencies -contains $_.currency -and $_.ledgerAccountId })
        if ($ready.Count -eq $seedCurrencies.Count) { return $ready }
        if ((Get-Date) -gt $deadline) {
            Die "timed out waiting for EUR/GBP/USD/JPY wallets to all have a ledgerAccountId (have: $(($accounts | ForEach-Object { $_.currency }) -join ', '))"
        }
        Start-Sleep -Seconds 1
    }
}

function Invoke-SeedUser([string] $Email, [string] $Password, [string] $FirstName, [string] $LastName) {
    Say "seeding $Email..."
    Register-SeedUser -Email $Email -Password $Password -FirstName $FirstName -LastName $LastName
    $accessToken = Get-SeedAccessToken -Email $Email -Password $Password
    $authHeader = @{ Authorization = "Bearer $accessToken" }

    Open-MissingWallets -AuthHeader $authHeader
    $accounts = Wait-ForAllWallets -AuthHeader $authHeader

    foreach ($account in $accounts) {
        Invoke-JsonPost -Uri "$ledgerBase/internal/ledger/admin/fund/$($account.ledgerAccountId)" -Body @{
            amountMinor = $seedAmountMinor
            currency    = $account.currency
        } -Headers $authHeader | Out-Null
        Say "  funded $($account.currency) wallet ($($account.ledgerAccountId)) with $seedAmountMinor minor units"
    }
}

# Reuses whatever card this seed user already has (same idempotent-retry spirit as
# Open-MissingWallets's 409-tolerant pattern) rather than minting a new one on every
# `seed --with-history` re-run - card issuance itself has no natural dedup key the way wallet
# opening does.
function Get-OrIssueSeedCard([hashtable] $AuthHeader, [System.Guid] $AccountId) {
    $existing = Invoke-RestMethod -Method Get -Uri "$gatewayBase/api/cards" -Headers $AuthHeader
    $existing = @($existing)
    if ($existing.Count -gt 0) {
        Say "  reusing existing card $($existing[0].id)"
        return $existing[0].id
    }

    $card = Invoke-JsonPost -Uri "$gatewayBase/api/cards" -Body @{
        accountId = $AccountId
        type      = 'VIRTUAL'
        label     = 'Seed history card'
        pin       = '1234'
    } -Headers $AuthHeader
    Say "  issued card $($card.id)"
    return $card.id
}

# Polls GET /api/analytics/summary the same way Wait-ForAllWallets polls /api/accounts/me -
# with a deadline, not a blind fixed sleep - since the settlement -> categorisation ->
# monthly_spend chain this depends on is asynchronous (several Kafka hops: card-service's
# settlement, then analytics-service's own consumer). A timeout here is only ever a warning:
# the card/batch/budget/vault calls above have already succeeded regardless of whether the
# projection has caught up by the time this function gives up.
function Wait-ForSeedHistorySummary([hashtable] $AuthHeader, [string] $Month) {
    $deadline = (Get-Date).AddSeconds(90)
    while ($true) {
        $summary = Invoke-RestMethod -Method Get -Uri "$gatewayBase/api/analytics/summary?month=$Month" -Headers $AuthHeader
        if (@($summary.categories).Count -gt 0) {
            Say "  analytics summary for $Month is non-empty ($(@($summary.categories).Count) categories)"
            return
        }
        if ((Get-Date) -gt $deadline) {
            Warn "  timed out waiting for a non-empty analytics summary for $Month - the settlement/categorisation consumer chain may still be catching up; the seeded card/batch/budget/vault themselves were all accepted"
            return
        }
        Start-Sleep -Seconds 3
    }
}

# task 06.11: "3 months of plausible history so the dashboard is not empty."
#
# IMPORTANT - why this produces one real month of volume, not three calendar months of
# backdated data: every event this generates gets its occurredAt from an injected
# java.time.Clock's clock.instant() - OutboxAppenderImpl for every outbox-based service, and
# simulator-service's own CardNetworkEventPublisher for the AuthorizationRequestedV1/
# SettlementRequestedV1 events a spend batch produces - always the real wall clock. simulator-
# service's SimulatedClock (what /simulator/clock/advance-day moves) is a pure in-memory day
# counter with no relationship to wall-clock time at all (see its own javadoc): it only decides
# which of the simulator's *own* pending spends are due for a settlement batch on a given
# advance-day call. It does not, and cannot, backdate occurredAt. So there is no supported way
# to make this land in three genuinely different past calendar months without either changing
# Clock-injection in two services' production code just to serve a demo, or writing directly
# into analytics-service's tables - both explicitly out of scope for this task (see
# agent/state.json's 06.11 notes). What this function does instead is generate three months'
# worth of realistic transaction *volume and variety* - many merchants, many categories, a
# meaningful batch of settled transactions - landing honestly in whatever the real current
# month is when it runs, which is exactly enough for the donut/trend/budget/top-merchants views
# to all have real, varied, non-trivial data to show.
function Invoke-SeedHistory([string] $Email, [string] $Password) {
    Say "seeding spend history for $Email..."
    $accessToken = Get-SeedAccessToken -Email $Email -Password $Password
    $authHeader = @{ Authorization = "Bearer $accessToken" }

    $accounts = Invoke-RestMethod -Method Get -Uri "$gatewayBase/api/accounts/me" -Headers $authHeader
    $eurAccount = @($accounts) | Where-Object { $_.currency -eq 'EUR' } | Select-Object -First 1
    if (-not $eurAccount) { Die "$Email has no EUR wallet yet - run 'tasks.ps1 seed' (without --with-history) first" }

    # The base $seedAmountMinor (EUR 500) funded above is nowhere near enough headroom for a
    # $historyBatchCount-authorization batch once merchants like Apple Store/airlines/hotels are
    # in the mix - fund it directly rather than starving the batch partway through and ending up
    # with a thin, mostly-declined settled feed. Not idempotent on a re-run, same as the base
    # funding step above (see Invoke-SeedUser's own comment) - re-running `seed --with-history`
    # simply tops up further, which is harmless for demo data.
    Invoke-JsonPost -Uri "$ledgerBase/internal/ledger/admin/fund/$($eurAccount.ledgerAccountId)" -Body @{
        amountMinor = $historyEurTopUpMinor
        currency    = 'EUR'
    } -Headers $authHeader | Out-Null

    $cardId = Get-OrIssueSeedCard -AuthHeader $authHeader -AccountId $eurAccount.id

    Say "  submitting a $historyBatchCount-authorization spend batch on card $cardId..."
    Invoke-RestMethod -Method Post -Uri "$simulatorBase/simulator/cards/spend/batch?count=$historyBatchCount" `
        -ContentType 'application/json' -Body (@{ cardId = $cardId } | ConvertTo-Json) | Out-Null

    # A batch spreads its settlement dates over currentDay+1..+3 (CardSpendService's own
    # javadoc), so a handful of advance-day calls - not one per simulated "day" of 90 - clears
    # the whole batch; a couple of spares cover any earlier seed runs still in flight from a
    # prior `--with-history` invocation against the same long-lived simulator process.
    Say '  advancing the simulator clock so the batch settles...'
    for ($i = 0; $i -lt $historyAdvanceDayCount; $i++) {
        Invoke-RestMethod -Method Post -Uri "$simulatorBase/simulator/clock/advance-day" | Out-Null
    }

    $monthFirst = (Get-Date -Day 1).ToString('yyyy-MM-dd')

    Say "  setting a $historyBudgetCategory budget of $historyBudgetLimitMinor EUR-minor for $monthFirst..."
    # EUR 200: comfortably below what ~1/7 of a 100-spend batch landing on the catalogue's five
    # grocery merchants (Tesco/Lidl/Aldi/SuperValu/Carrefour, all EUR) plausibly totals, so both
    # the 80% and 100% thresholds clear for real rather than by exact calibration.
    Invoke-RestMethod -Method Put -Uri "$gatewayBase/api/analytics/budgets" -ContentType 'application/json' -Body (@{
        category   = $historyBudgetCategory
        month      = $monthFirst
        limitMinor = $historyBudgetLimitMinor
        currency   = 'EUR'
    } | ConvertTo-Json) -Headers $authHeader | Out-Null

    Say "  creating a round-up vault sourced from card $cardId..."
    try {
        Invoke-JsonPost -Uri "$gatewayBase/api/analytics/vaults" -Body @{
            name                = 'Seed round-up vault'
            currency            = 'EUR'
            goalMinor           = $historyVaultGoalMinor
            roundupEnabled      = $true
            roundupSourceCardId = $cardId
        } -Headers $authHeader | Out-Null
    } catch {
        if ((Get-HttpStatus $_) -eq 409) {
            Say '  round-up vault already exists'
        } else {
            Die "creating the round-up vault for $Email failed: $($_.Exception.Message)"
        }
    }

    Wait-ForSeedHistorySummary -AuthHeader $authHeader -Month $monthFirst
}

# task 07.7 (plans/phase-07-resilience-and-scale.md's ### Replay). analytics-service's own two
# consumer topics - see its LedgerEntryKafkaListener/CardAuthorizationKafkaListener.
$analyticsGroupId = 'analytics-service'
$analyticsTopics  = @('ledger.entry.v1', 'card.authorization.v1')

function Wait-ForContainerHealthy([string[]] $Compose, [string] $Service, [int] $TimeoutSeconds = 180) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
        $cid = (& docker compose @Compose ps -q $Service | Select-Object -First 1)
        if ($cid) {
            $health = (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' $cid).Trim()
            if ($health -eq 'healthy' -or $health -eq 'no-healthcheck') { return }
        }
        if ((Get-Date) -gt $deadline) { Die "timed out waiting for $Service to report healthy" }
        Start-Sleep -Seconds 2
    }
}

# plans/phase-07-resilience-and-scale.md's ### Replay, flavour 1 ("projection rebuild"): "drop
# analytics-service's database, reset its consumer group to offset 0, replay. Must produce
# identical aggregates and zero notifications." The schema is dropped and recreated rather than
# the whole database: `analytics`, like every role in infra/postgres/init-databases.sql, only
# has LOGIN rights on its own already-connected database (the same no-superuser constraint
# managed Postgres imposes for real), and under Postgres 15+'s pg_database_owner default a
# database-owning role already owns its own `public` schema outright, so this needs no extra
# GRANT. Flyway re-migrates that empty schema back to V3-V5 on the service's next startup - the
# same path a brand-new environment's first boot takes - which is also why this is a schema
# reset and never ad-hoc CREATE TABLE anywhere in this project (CLAUDE.md hard rule 8, "Flyway
# owns all schema").
#
# Stops the service first, not merely for a clean schema drop: plans/phase-07-resilience-and-
# scale.md's own Risks & gotchas warns "consumer-group offset reset requires the group to be
# empty... or the reset silently no-ops."
function Invoke-AnalyticsRebuild {
    Say 'stopping analytics-service (a consumer-group offset reset silently no-ops against a running member)...'
    & docker compose @composeServices stop analytics-service
    if ($LASTEXITCODE -ne 0) { Die 'stopping analytics-service failed' }

    Say 'dropping and recreating the analytics schema...'
    & docker compose @compose exec -T postgres psql -U analytics -d analytics -v ON_ERROR_STOP=1 `
        -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
    if ($LASTEXITCODE -ne 0) { Die 'resetting the analytics schema failed' }

    Say "resetting the $analyticsGroupId consumer group to offset zero on $($analyticsTopics -join ', ')..."
    $topicArgs = @()
    foreach ($topic in $analyticsTopics) { $topicArgs += @('--topic', $topic) }
    & docker compose @compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server 127.0.0.1:9092 `
        --group $analyticsGroupId --reset-offsets --to-earliest @topicArgs --execute
    if ($LASTEXITCODE -ne 0) { Die 'resetting the analytics-service consumer group offsets failed' }

    Say 'restarting analytics-service - Flyway re-migrates the schema, then the consumers replay from offset zero...'
    & docker compose @composeServices up -d analytics-service
    if ($LASTEXITCODE -ne 0) { Die 'restarting analytics-service failed' }

    Wait-ForContainerHealthy -Compose $composeServices -Service 'analytics-service'
    Say 'analytics-service is healthy again. Check the dashboard for identical aggregates, and confirm no new notification arrived.'
}

# plans/phase-07-resilience-and-scale.md's ### Replay, flavour 2 ("point-in-time query"):
# "what was every balance last Tuesday at 14:00?" - read-only, and unlike analytics-rebuild
# above never touches a real consumer group (PointInTimeService reads with its own throwaway
# group id, same reasoning as common-messaging's DltAdminService). Needs a bearer token the same
# way the funding calls inside Invoke-SeedUser do: this project has no separate "operator"
# identity yet, so it reuses whichever seed user is already registered.
function Invoke-PointInTimeQuery([string] $Timestamp) {
    if (-not $Timestamp) { Die 'usage: tasks.ps1 point-in-time <ISO-8601 instant, e.g. 2026-09-23T14:00:00Z>' }
    try {
        $asOf = ([DateTimeOffset]::Parse($Timestamp)).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss\Z")
    } catch {
        Die "'$Timestamp' is not a parseable timestamp: $($_.Exception.Message)"
    }

    try {
        $accessToken = Get-SeedAccessToken -Email 'alice.seed@revolutclone.local' -Password 'Password1!'
    } catch {
        Die "could not log in as the seed user for a bearer token - run `"tasks.ps1 seed`" first. Underlying error: $($_.Exception.Message)"
    }
    $authHeader = @{ Authorization = "Bearer $accessToken" }

    Say "replaying ledger.entry.v1 up to $asOf..."
    $uri = "$ledgerBase/internal/ledger/admin/point-in-time?asOf=$([uri]::EscapeDataString($asOf))"
    $result = Invoke-RestMethod -Method Get -Uri $uri -Headers $authHeader

    if (@($result.balances).Count -eq 0) {
        Say "no ledger.entry.v1 activity on or before $asOf."
        return
    }
    $result.balances | Select-Object ledgerAccountId, currency, balanceAfterMinor, occurredAt |
        Format-Table -AutoSize | Out-String | Write-Host
}

Push-Location $repo
try {
    switch ($Command) {
        'up' {
            Say 'starting infra stack (postgres, kafka, kafka-ui, redis, keycloak)...'
            & docker compose @compose up -d
            if ($LASTEXITCODE -ne 0) { Die 'docker compose up failed' }

            Say 'waiting for every container to report healthy...'
            $services = @(& docker compose @compose ps --services)
            $pending  = [System.Collections.Generic.HashSet[string]]::new([string[]] $services)
            $deadline = (Get-Date).AddMinutes(3)

            while ($pending.Count -gt 0) {
                if ((Get-Date) -gt $deadline) {
                    Die "timed out waiting for: $($pending -join ', ')"
                }
                foreach ($svc in @($pending)) {
                    $cid = (& docker compose @compose ps -q $svc | Select-Object -First 1)
                    if (-not $cid) { continue }
                    $health = (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' $cid).Trim()
                    if ($health -eq 'healthy' -or $health -eq 'no-healthcheck') {
                        Say "  $svc : $health"
                        $pending.Remove($svc) | Out-Null
                    }
                }
                if ($pending.Count -gt 0) { Start-Sleep -Seconds 2 }
            }
            Say 'all containers healthy. Run ".\tasks.ps1 topics" to create the Kafka topic catalogue.'
        }

        'down' {
            Say 'stopping infra stack (volumes kept)...'
            & docker compose @compose down
        }

        'reset' {
            Warn 'stopping infra stack and DROPPING its volumes - postgres/kafka/keycloak data will be lost.'
            & docker compose @compose down -v
        }

        'logs' {
            if (-not $Target) { Die 'usage: tasks.ps1 logs <service>   (postgres|kafka|kafka-ui|redis|keycloak)' }
            if ($Follow) {
                & docker compose @compose logs -f $Target
            } else {
                & docker compose @compose logs --tail=200 $Target
            }
        }

        'psql' {
            if (-not $Target) { Die 'usage: tasks.ps1 psql <database>   (identity|account|ledger|payment|fx|card|analytics|notification)' }
            if ($knownDbs -notcontains $Target) {
                Warn "'$Target' is not one of the known databases ($($knownDbs -join ', ')) - trying anyway."
            }
            & docker compose @compose exec postgres psql -U $Target -d $Target
        }

        'topics' {
            if ($Describe) {
                Say 'describing every declared topic (cleanup.policy, retention.ms)...'
                foreach ($topic in (Get-TopicNames)) {
                    & docker compose @compose exec -T kafka /opt/kafka/bin/kafka-topics.sh `
                        --bootstrap-server 127.0.0.1:9092 --describe --topic $topic
                }
            } else {
                Say 'creating the topic catalogue (idempotent)...'
                # Piping the script's content through PowerShell's pipeline into `exec -T`'s
                # stdin re-encodes line endings along the way and corrupts the script (a
                # trailing stray \r reads as a bogus final command under `set -e`). Copying it
                # into the container and running it as a file avoids that entirely.
                $cid = (& docker compose @compose ps -q kafka | Select-Object -First 1)
                if (-not $cid) { Die 'kafka container is not running - run "tasks.ps1 up" first' }
                & docker cp (Join-Path $repo 'infra/kafka/create-topics.sh') "${cid}:/tmp/create-topics.sh"
                & docker compose @compose exec -T kafka bash /tmp/create-topics.sh
            }
        }

        'seed' {
            # Deliberately not "alice@demo.io"/"bob@demo.io": those two are pre-provisioned
            # directly in the Keycloak realm export (infra/keycloak/revolut-realm.json, task
            # 00.11) with no identity-service profile or account-service wallets behind them
            # yet, and RegistrationService.register hard-fails (409) on an email Keycloak
            # already knows - registering a NEW email is the only path this script's own
            # public API surface actually supports today.
            Say 'seeding demo users through the public API - the gateway, then ledger-service directly for funding...'
            try {
                Invoke-SeedUser -Email 'alice.seed@revolutclone.local' -Password 'Password1!' -FirstName 'Alice' -LastName 'Seed'
                Invoke-SeedUser -Email 'bob.seed@revolutclone.local' -Password 'Password1!' -FirstName 'Bob' -LastName 'Seed'

                if ($WithHistory) {
                    Say 'generating spend history for the Analytics dashboard/Vaults screen (--with-history)...'
                    Invoke-SeedHistory -Email 'alice.seed@revolutclone.local' -Password 'Password1!'
                    Invoke-SeedHistory -Email 'bob.seed@revolutclone.local' -Password 'Password1!'
                }
            } catch {
                if ($_.Exception -is [System.Net.Http.HttpRequestException] -or $_.Exception.Message -match 'connect') {
                    Die "could not reach the stack - is it up? `"tasks.ps1 up`" starts infra; the application services (gateway, identity, account, ledger, fx, and - for --with-history - card, simulator, analytics) need `"docker compose --profile services up -d --build`" too. Underlying error: $($_.Exception.Message)"
                }
                throw
            }
            $summaryMessage = 'seed complete: alice.seed@revolutclone.local / bob.seed@revolutclone.local, password Password1!, each with EUR/GBP/USD/JPY wallets funded.'
            if ($WithHistory) {
                $summaryMessage += ' Plus a card, spend history, a Groceries budget and a round-up vault for each.'
            }
            Say $summaryMessage
        }

        'analytics-rebuild' {
            Invoke-AnalyticsRebuild
        }

        'point-in-time' {
            Invoke-PointInTimeQuery -Timestamp $Target
        }
    }
}
finally { Pop-Location }
