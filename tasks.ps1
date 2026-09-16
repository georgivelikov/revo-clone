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
#>
param(
    [Parameter(Position = 0, Mandatory = $true)]
    [ValidateSet('up', 'down', 'reset', 'logs', 'psql', 'topics')]
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
    }
}
finally { Pop-Location }
