#!/usr/bin/env pwsh
<#
One-command local start: infra + all services + web, wait until healthy, create topics, seed.

  .\start.ps1               bring everything up (builds images only if missing), seed demo data
  .\start.ps1 -Build        force an image rebuild first
  .\start.ps1 -SkipSeed     skip the demo-data seed
  .\start.ps1 -Obs          also start the observability stack (Prometheus/Grafana)
  .\start.ps1 -Down         stop everything, keep data
  .\start.ps1 -Reset        stop everything and DROP all volumes (fresh DBs / Keycloak realm)

Idempotent: safe to run against an already-running stack.
#>
param(
    [switch] $Build,
    [switch] $SkipSeed,
    [switch] $Obs,
    [switch] $Down,
    [switch] $Reset,
    [int]    $TimeoutMinutes = 10
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Say ($m) { Write-Host "[start] $m" -ForegroundColor Cyan }
function Die ($m) { Write-Host "[start] $m" -ForegroundColor Red; exit 1 }

Push-Location $PSScriptRoot
try {
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) { Die 'Docker is not running - start Docker Desktop and retry.' }

    $files = @('-f', 'docker-compose.infra.yml', '-f', 'docker-compose.yml')
    if ($Obs) { $files += @('-f', 'docker-compose.obs.yml') }
    $compose = $files + @('--profile', 'services')

    if ($Down -or $Reset) {
        # Always include the obs file so its containers are stopped too.
        $all = @('-f', 'docker-compose.infra.yml', '-f', 'docker-compose.yml', '-f', 'docker-compose.obs.yml', '--profile', 'services')
        if ($Reset) { Say 'stopping everything and dropping volumes...'; & docker compose @all down -v }
        else        { Say 'stopping everything (volumes kept)...';       & docker compose @all down }
        exit $LASTEXITCODE
    }

    $env:GIT_SHA = (& git rev-parse --short HEAD 2>$null)
    if (-not $env:GIT_SHA) { $env:GIT_SHA = 'dev' }

    $upArgs = @('up', '-d')
    if ($Build) { $upArgs += '--build' }
    Say "starting stack (GIT_SHA=$($env:GIT_SHA))..."
    & docker compose @compose @upArgs
    if ($LASTEXITCODE -ne 0) { Die 'docker compose up failed' }

    Say 'waiting for every container to be healthy...'
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    # `config --services` lists only what these files + profile define; `ps --services` also
    # reports containers from other compose files in the same project (e.g. the obs stack).
    $services = @(& docker compose @compose config --services)
    $pending  = [System.Collections.Generic.HashSet[string]]::new([string[]] $services)
    while ($pending.Count -gt 0) {
        if ((Get-Date) -gt $deadline) { Die "timed out waiting for: $($pending -join ', ')" }
        foreach ($svc in @($pending)) {
            # -a: one-shot containers (e.g. kafka-topics-init) exit 0 and are hidden from plain `ps`.
            $cid = (& docker compose @compose ps -a -q $svc | Select-Object -First 1)
            if (-not $cid) { continue }
            $state = (& docker inspect --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{.State.ExitCode}}' $cid).Trim()
            $status, $health, $exit = $state -split '\|'
            if ($status -eq 'exited' -and $exit -eq '0') {
                Say "  $svc : completed (exit 0)"
                $pending.Remove($svc) | Out-Null
                continue
            }
            if ($status -eq 'exited' -or $status -eq 'dead') { Die "$svc exited with code $exit - see: docker compose $($compose -join ' ') logs $svc" }
            if ($health -eq 'unhealthy') { Die "$svc is unhealthy - see: docker compose $($compose -join ' ') logs $svc" }
            if ($status -eq 'running' -and ($health -eq 'healthy' -or $health -eq 'none')) {
                Say "  $svc : ok"
                $pending.Remove($svc) | Out-Null
            }
        }
        if ($pending.Count -gt 0) { Start-Sleep -Seconds 3 }
    }

    Say 'creating Kafka topics (idempotent)...'
    & (Join-Path $PSScriptRoot 'tasks.ps1') topics
    if ($LASTEXITCODE -ne 0) { Die 'topic creation failed' }

    if (-not $SkipSeed) {
        Say 'seeding demo data (idempotent)...'
        & (Join-Path $PSScriptRoot 'tasks.ps1') seed
        if ($LASTEXITCODE -ne 0) { Die 'seeding failed' }
    }

    Write-Host ''
    Say 'READY'
    Say '  App        http://localhost:5173   (alice@demo.io / demo)'
    Say '  Gateway    http://localhost:8080'
    Say '  Keycloak   http://localhost:8180'
    Say '  Kafka UI   http://localhost:8090'
    if ($Obs) { Say '  Grafana    http://localhost:3000' }
}
finally { Pop-Location }
