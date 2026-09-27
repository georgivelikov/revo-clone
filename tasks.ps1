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
#>
param(
    [Parameter(Position = 0, Mandatory = $true)]
    [ValidateSet('up', 'down', 'reset', 'logs', 'psql', 'topics', 'seed')]
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

$Describe = $Rest -contains '--describe'
$Follow   = ($Rest -contains '--follow') -or ($Rest -contains '-f')

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
$gatewayBase   = 'http://localhost:8080'
$ledgerBase    = 'http://localhost:8083'
$tokenEndpoint = 'http://localhost:8180/realms/revolut/protocol/openid-connect/token'
$seedCurrencies = @('EUR', 'GBP', 'USD', 'JPY')
# One round number for every currency: JPY has zero minor digits, so 50000 minor units is
# ¥50,000 - the same order of magnitude as 500.00 of the other three, not a currency
# conversion. The phase names no specific amount, only "GBP/USD/JPY balances".
$seedAmountMinor = 50000

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
            } catch {
                if ($_.Exception -is [System.Net.Http.HttpRequestException] -or $_.Exception.Message -match 'connect') {
                    Die "could not reach the stack - is it up? `"tasks.ps1 up`" starts infra; the application services (gateway, identity, account, ledger, fx) need `"docker compose --profile services up -d --build`" too. Underlying error: $($_.Exception.Message)"
                }
                throw
            }
            Say 'seed complete: alice.seed@revolutclone.local / bob.seed@revolutclone.local, password Password1!, each with EUR/GBP/USD/JPY wallets funded.'
        }
    }
}
finally { Pop-Location }
