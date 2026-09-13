#!/usr/bin/env pwsh
<#
Runs the mechanically checkable parts of a phase's `## Verification` section, and prints
the rest so the log records what still needs a human or the verifier subagent's eyes.

Called by gate.ps1 check 7 on the last task of a phase. Exit 0 = pass.

Deliberately conservative: a check whose preconditions are absent reports n/a rather than
failing, so early phases are not blocked by infrastructure that does not exist yet. What it
does assert, it asserts hard.
#>
param(
    [Parameter(Mandatory = $true)][string] $Phase
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo

$failures = New-Object System.Collections.Generic.List[string]
function Fail([string] $m) { $script:failures.Add($m); Write-Output "  FAIL  $m" }
function Pass([string] $m) { Write-Output "  ok    $m" }
function Skip([string] $m) { Write-Output "  n/a   $m" }

try {
    Write-Output "=== phase-verify: $Phase ==="

    # --- 1. config hygiene: no localhost outside a local profile ----------------------
    # Phase 00's Verification says this explicitly, and it is cheap to assert on every
    # phase boundary rather than only once.
    $configFiles = @(Get-ChildItem -Path $repo -Recurse -File -Include '*.yml', '*.yaml', '*.properties' `
                        -ErrorAction SilentlyContinue |
                     Where-Object { $_.FullName -notmatch '[\\/](target|node_modules|\.git)[\\/]' })
    $leaks = @()
    foreach ($f in $configFiles) {
        if ($f.Name -match 'local') { continue }
        $rel = $f.FullName.Substring($repo.Length + 1)
        $n = 0
        foreach ($line in (Get-Content -LiteralPath $f.FullName -ErrorAction SilentlyContinue)) {
            $n++
            if ($line -match 'localhost' -and $line -notmatch '^\s*#') { $leaks += "${rel}:${n}" }
        }
    }
    if ($configFiles.Count -eq 0)  { Skip 'config hygiene: no config files yet' }
    elseif ($leaks.Count -gt 0)    { Fail "config hygiene: localhost outside a local profile -> $($leaks -join ', ')" }
    else                           { Pass "config hygiene: $($configFiles.Count) config file(s), no localhost leaks" }

    # --- 2. containers healthy --------------------------------------------------------
    $composeFiles = @(Get-ChildItem -Path $repo -File -Filter 'docker-compose*.y*ml' -ErrorAction SilentlyContinue)
    if ($composeFiles.Count -eq 0) {
        Skip 'containers: no docker-compose file yet'
    } elseif (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Fail 'containers: docker is not on PATH'
    } else {
        $psJson = & docker compose ps --format json 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or -not $psJson.Trim()) {
            Fail 'containers: `docker compose ps` returned nothing - is the stack up?'
        } else {
            $rows = @()
            foreach ($line in ($psJson -split "`r?`n")) {
                if (-not $line.Trim()) { continue }
                try { $rows += ($line | ConvertFrom-Json) } catch { }
            }
            if ($rows.Count -eq 0) {
                Fail 'containers: no services running'
            } else {
                $bad = @($rows | Where-Object {
                    $_.State -ne 'running' -or
                    ($_.Health -and $_.Health -notin @('healthy', ''))
                })
                if ($bad.Count -gt 0) {
                    Fail "containers: not healthy -> $(($bad | ForEach-Object { "$($_.Service)=$($_.State)/$($_.Health)" }) -join ', ')"
                } else {
                    Pass "containers: $($rows.Count) service(s) running and healthy"
                }
            }
        }
    }

    # --- 3. Kafka topics match the declared catalogue ---------------------------------
    $topicScript = Join-Path $repo 'infra/kafka/create-topics.sh'
    if (-not (Test-Path $topicScript)) {
        Skip 'topics: infra/kafka/create-topics.sh not written yet'
    } else {
        $declared = @()
        foreach ($line in (Get-Content -LiteralPath $topicScript)) {
            if ($line -match '--topic\s+([A-Za-z0-9._-]+)') { $declared += $Matches[1] }
        }
        $declared = @($declared | Sort-Object -Unique)
        $kafkaCid = (& docker compose ps -q kafka 2>$null | Select-Object -First 1)
        if (-not $kafkaCid) {
            Fail 'topics: the kafka container is not running'
        } else {
            $actual = & docker exec $kafkaCid kafka-topics.sh --bootstrap-server localhost:9092 --list 2>&1
            if ($LASTEXITCODE -ne 0) {
                Fail "topics: could not list topics - $($actual | Out-String)"
            } else {
                $missing = @($declared | Where-Object { $_ -notin $actual })
                if ($missing.Count -gt 0) { Fail "topics: declared but missing -> $($missing -join ', ')" }
                else { Pass "topics: all $($declared.Count) declared topic(s) exist" }
            }
        }
    }

    # --- 4. print the phase's own Verification block -----------------------------------
    # Not everything in it is mechanically checkable (Kafka UI screens, a token's claims).
    # Recording it in the log is what lets the verifier subagent and a human audit the rest.
    $phasePath = Join-Path $repo (Join-Path 'plans' $Phase)
    if (Test-Path $phasePath) {
        Write-Output ''
        Write-Output "--- $Phase `## Verification` (for the verifier subagent to confirm) ---"
        $inBlock = $false
        foreach ($line in (Get-Content -LiteralPath $phasePath)) {
            if ($line -match '^## Verification') { $inBlock = $true; continue }
            if ($inBlock -and $line -match '^## ')  { break }
            if ($inBlock) { Write-Output "    $line" }
        }
    }

    Write-Output ''
    if ($failures.Count -gt 0) {
        Write-Output "PHASE VERIFY RED - $($failures.Count) failure(s)"
        exit 1
    }
    Write-Output 'PHASE VERIFY GREEN'
    exit 0
}
finally { Pop-Location }
