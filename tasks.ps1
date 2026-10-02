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
  tasks.ps1 seed                idempotent demo-data generator (tools/seed/seed.ps1,
                                 plans/phase-09-polish.md task 09.4): reconciles the four
                                 pre-provisioned Keycloak demo users (alice/bob/carol/dan@demo.io,
                                 password `demo`), funds their EUR/GBP/USD wallets, seeds ~150
                                 card transactions across Alice's and Bob's cards, a handful of
                                 P2P transfers and several FX exchanges between the four, and
                                 exactly two budgets (one comfortable, one breached) and two
                                 vaults - then asserts the ledger's zero-sum invariant. Safe to
                                 run any number of times; see that file's own header comment for
                                 exactly how each step stays idempotent. Supersedes the
                                 self-registered alice.seed@/bob.seed@ users and the
                                 `--with-history` flag an earlier phase's version of this command
                                 used - plans/phase-06-vaults-analytics.md task 11's own text
                                 still describes that superseded shape. Needs the full stack up,
                                 not just infra: `docker compose --profile services up -d --build`
                                 alongside `tasks.ps1 up`.
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

$gatewayBase   = 'http://localhost:8080'
$ledgerBase    = 'http://localhost:8083'
$tokenEndpoint = 'http://localhost:8180/realms/revolut/protocol/openid-connect/token'

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
# group id, same reasoning as common-messaging's DltAdminService). Needs a bearer token - uses
# the demo user alice@demo.io (tools/seed/seed.ps1, task 09.4) since any authenticated token
# works here and that one is always available once `tasks.ps1 seed` has run.
function Invoke-PointInTimeQuery([string] $Timestamp) {
    if (-not $Timestamp) { Die 'usage: tasks.ps1 point-in-time <ISO-8601 instant, e.g. 2026-09-23T14:00:00Z>' }
    try {
        $asOf = ([DateTimeOffset]::Parse($Timestamp)).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss\Z")
    } catch {
        Die "'$Timestamp' is not a parseable timestamp: $($_.Exception.Message)"
    }

    try {
        $accessToken = Get-SeedAccessToken -Email 'alice@demo.io' -Password 'demo'
    } catch {
        Die "could not log in as the demo user for a bearer token - run `"tasks.ps1 seed`" first. Underlying error: $($_.Exception.Message)"
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
            & (Join-Path $repo 'tools/seed/seed.ps1')
            if ($LASTEXITCODE -ne 0) { Die 'seeding failed - see tools/seed/seed.ps1''s own output above' }
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
