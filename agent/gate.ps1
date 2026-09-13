#!/usr/bin/env pwsh
<#
The ONLY authority on whether a task is done. Exit 0 means done; nothing else in the
system may declare a task complete.

Most of this file's weight goes on making a green result impossible to fake, because
an agent that has failed `mvn verify` twice will, on the third attempt, consider
deleting the test. Orchestration is the easy half.

Permission-denied to the agent, along with tripwire.ps1. A gate the agent can edit is
not a gate.

  -TaskId <id>   check the named test classes for that task from agent/state.json
  -SkipBuild     diagnostics only; never use this in a run
#>
param(
    [string] $TaskId,
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo

$failures = New-Object System.Collections.Generic.List[string]
function Fail([string] $m) { $script:failures.Add($m); Write-Output "  FAIL  $m" }
function Pass([string] $m) { Write-Output "  ok    $m" }

try {
    Write-Output "=== gate.ps1 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ==="

    $logDir = Join-Path $repo 'agent/log'
    if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

    # --- load the task from the ledger, if there is one -------------------------------
    $task = $null
    $statePath = Join-Path $repo 'agent/state.json'
    if ($TaskId -and (Test-Path $statePath)) {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        $task  = $state.tasks | Where-Object { $_.id -eq $TaskId } | Select-Object -First 1
        if (-not $task) { Fail "task '$TaskId' not found in agent/state.json" }
    }

    $taskKind = 'build'
    if ($task -and $task.PSObject.Properties.Name -contains 'kind' -and $task.kind) {
        $taskKind = [string]$task.kind
    }

    # --- 1. the agent actually did something ------------------------------------------
    # A 'verify' task (e.g. a toolchain check) legitimately produces no diff.
    $base = $null
    try { $base = (& git merge-base master HEAD 2>$null | Select-Object -First 1) } catch { }
    # Harness bookkeeping must not count as the agent's work. run.ps1 writes state.json
    # (status -> in_progress) BEFORE launching claude, so counting it would make check 1
    # pass even when the agent did nothing at all.
    $bookkeeping = '^(agent/state\.json|agent/log/|agent/harness-settings\.json)'

    $nameArgs = if ($base) { @('diff', '--name-only', $base) } else { @('diff', '--name-only', 'HEAD') }
    $changed = @(& git @nameArgs 2>$null | Where-Object { $_ -and $_ -notmatch $bookkeeping })

    # Untracked files are in no diff, so count them too - otherwise a task whose whole
    # output is new files reads as "did nothing".
    $untracked = @(& git ls-files --others --exclude-standard 2>$null |
                     Where-Object { $_ -and $_ -notmatch '[\/]target[\/]' -and $_ -notmatch $bookkeeping })

    $stat = ''
    if ($changed.Count -gt 0 -or $untracked.Count -gt 0) {
        $stat = "$($changed.Count) changed, $($untracked.Count) new file(s)"
    }

    if ($taskKind -eq 'verify') {
        Pass "check 1 diff: skipped (task kind 'verify')"
    } elseif (-not $stat) {
        Fail 'check 1 diff: no changes at all - the task produced nothing'
    } else {
        Pass "check 1 diff: $stat"
    }

    # --- 2. tripwire ------------------------------------------------------------------
    $tripwire = Join-Path $repo 'agent/tripwire.ps1'
    if (-not (Test-Path $tripwire)) {
        Fail 'check 2 tripwire: agent/tripwire.ps1 is missing'
    } else {
        $twOut = & pwsh -NoProfile -ExecutionPolicy Bypass -File $tripwire 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) {
            Fail "check 2 tripwire: violations found`n$twOut"
        } else {
            Pass 'check 2 tripwire: clean'
        }
    }

    # --- 3. full reactor build --------------------------------------------------------
    $surefireDirs = @()
    if ($SkipBuild) {
        Write-Output '  SKIP  check 3 build: -SkipBuild was passed (diagnostics only)'
    } elseif (-not (Test-Path (Join-Path $repo 'pom.xml'))) {
        # Before phase 00 task 2 there is no reactor. Say so rather than passing silently.
        Write-Output '  n/a   check 3 build: no root pom.xml yet'
    } else {
        $mvnOut = & mvn -q -B clean verify 2>&1 | Out-String
        $mvnExit = $LASTEXITCODE
        $mvnOut | Set-Content -LiteralPath (Join-Path $logDir 'mvn-last.txt') -Encoding utf8
        if ($mvnExit -ne 0) {
            $tail = ($mvnOut -split "`r?`n" | Select-Object -Last 40) -join "`n"
            Fail "check 3 build: mvn clean verify failed (exit $mvnExit). Last 40 lines:`n$tail"
        } else {
            Pass 'check 3 build: mvn clean verify green'
        }
        $surefireDirs = @(Get-ChildItem -Path $repo -Recurse -Directory -Force `
                            -Include 'surefire-reports', 'failsafe-reports' -ErrorAction SilentlyContinue)
    }

    # --- parse the test reports once, reused by checks 4 and 5 ------------------------
    $totalTests = 0
    $totalBad   = 0
    $classNames = New-Object System.Collections.Generic.HashSet[string]

    foreach ($dir in $surefireDirs) {
        foreach ($xml in Get-ChildItem -Path $dir.FullName -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue) {
            try {
                [xml] $doc = Get-Content -LiteralPath $xml.FullName -Raw
                $suite = $doc.testsuite
                if (-not $suite) { continue }
                $totalTests += [int] $suite.tests
                $totalBad   += [int] $suite.failures + [int] $suite.errors + [int] $suite.skipped
                if ($suite.name) { $classNames.Add(($suite.name -split '\.')[-1]) | Out-Null }
            } catch {
                Fail "check 3 build: unreadable test report $($xml.Name)"
            }
        }
    }

    # --- 4. every test the phase names exists and passes ------------------------------
    if (-not $task) {
        Write-Output '  n/a   check 4 named tests: no -TaskId given'
    } elseif (-not $task.tests -or @($task.tests).Count -eq 0) {
        # Many tasks create no test of their own, and phases 07-09 declare no Tests table at
        # all. The baseline in check 5 is what covers these against regression.
        Write-Output '  n/a   check 4 named tests: this task declares none'
    } else {
        foreach ($name in $task.tests) {
            $src = Get-ChildItem -Path $repo -Recurse -File -Filter "$name.java" -ErrorAction SilentlyContinue |
                       Select-Object -First 1
            if (-not $src) {
                Fail "check 4 named tests: $name.java does not exist"
                continue
            }
            if ($SkipBuild) {
                Pass "check 4 named tests: $name source present (build skipped)"
                continue
            }
            if (-not $classNames.Contains($name)) {
                Fail "check 4 named tests: $name exists but did not run"
            } else {
                Pass "check 4 named tests: $name ran"
            }
        }
    }

    if (-not $SkipBuild -and $surefireDirs.Count -gt 0 -and $totalBad -gt 0) {
        Fail "check 4 named tests: $totalBad failed, errored or SKIPPED test(s) across the reactor - skipped counts as a failure here"
    }

    # --- 5. the test count must never drop --------------------------------------------
    # This single check catches most forms of cheating that individually look innocent.
    $baselinePath = Join-Path $logDir 'baseline.json'
    if ($SkipBuild) {
        Write-Output '  n/a   check 5 baseline: build skipped'
    } elseif ($surefireDirs.Count -eq 0) {
        Write-Output '  n/a   check 5 baseline: no test reports yet'
    } else {
        $previous = 0
        if (Test-Path $baselinePath) {
            $b = Get-Content -LiteralPath $baselinePath -Raw | ConvertFrom-Json
            $previous = [int] $b.testCount
        }
        if ($totalTests -lt $previous) {
            Fail "check 5 baseline: test count dropped from $previous to $totalTests - tests are fixed, never removed"
        } else {
            Pass "check 5 baseline: $totalTests tests (previous high $previous)"
        }
    }

    # --- 6. the ledger invariant, from phase 02 onward --------------------------------
    # The one invariant the whole project rests on, and cheap to check continuously.
    # It catches UNBALANCED entries; it cannot catch a consistently wrong sign
    # convention, which is why phase 02 keeps a human checkpoint.
    $ledgerUp = $false
    try {
        $psql = Get-Command psql -ErrorAction SilentlyContinue
        if ($psql) {
            $probe = & psql -h localhost -U ledger -d ledger -tAc "SELECT to_regclass('public.ledger_entry') IS NOT NULL" 2>$null
            if ($LASTEXITCODE -eq 0 -and $probe -and $probe.Trim() -eq 't') { $ledgerUp = $true }
        }
    } catch { }

    if (-not $ledgerUp) {
        Write-Output '  n/a   check 6 ledger invariant: ledger_entry not reachable yet (pre-phase-02)'
    } else {
        $unbalanced = & psql -h localhost -U ledger -d ledger -tAc `
            "SELECT transaction_id FROM ledger_entry GROUP BY transaction_id HAVING SUM(amount_minor) <> 0;" 2>&1
        if ($LASTEXITCODE -ne 0) {
            Fail "check 6 ledger invariant: query failed - $unbalanced"
        } elseif (($unbalanced | Out-String).Trim()) {
            Fail "check 6 ledger invariant: UNBALANCED transaction(s) - a hard failure regardless of the test suite:`n$unbalanced"
        } else {
            Pass 'check 6 ledger invariant: every transaction sums to zero'
        }
    }

    # --- 7. phase verification, on the last task of a phase ---------------------------
    if ($task -and $task.PSObject.Properties.Name -contains 'lastInPhase' -and $task.lastInPhase) {
        $verify = Join-Path $repo 'agent/phase-verify.ps1'
        if (Test-Path $verify) {
            $vOut = & pwsh -NoProfile -ExecutionPolicy Bypass -File $verify -Phase $task.phase 2>&1 | Out-String
            if ($LASTEXITCODE -ne 0) { Fail "check 7 phase verification failed:`n$vOut" }
            else { Pass "check 7 phase verification: $($task.phase)" }
        } else {
            Fail 'check 7 phase verification: agent/phase-verify.ps1 is missing and this is the last task in its phase'
        }
    } else {
        Write-Output '  n/a   check 7 phase verification: not the last task in a phase'
    }

    # --- verdict ----------------------------------------------------------------------
    Write-Output ''
    if ($failures.Count -gt 0) {
        Write-Output "GATE RED - $($failures.Count) check(s) failed:"
        foreach ($f in $failures) {
            $first = ($f -split "`n" | Select-Object -First 1)
            Write-Output "  - $first"
        }
        exit 1
    }

    # Record the new high-water mark only on a fully green gate.
    if (-not $SkipBuild -and $surefireDirs.Count -gt 0) {
        $prev = 0
        if (Test-Path $baselinePath) {
            $prev = [int] ((Get-Content -LiteralPath $baselinePath -Raw | ConvertFrom-Json).testCount)
        }
        if ($totalTests -gt $prev) {
            @{ testCount = $totalTests; recordedAt = (Get-Date -Format 'o'); taskId = $TaskId } |
                ConvertTo-Json | Set-Content -LiteralPath $baselinePath -Encoding utf8
        }
    }

    Write-Output 'GATE GREEN'
    exit 0
}
finally { Pop-Location }
