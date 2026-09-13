#!/usr/bin/env pwsh
<#
The driver. Picks the next runnable task from agent/state.json, runs one fresh `claude -p`
process on it, and merges onto master when the gate is green.

State lives in files and each task gets its own process, so compaction drift -- the failure
mode where an agent 200k tokens deep forgets that money is a `long` -- never happens, because
no process lives that long.

  pwsh agent/run.ps1 -Only 00.4     run exactly one task (use this for the smoke test)
  pwsh agent/run.ps1 -MaxTasks 4    run the next four tasks, then stop
  pwsh agent/run.ps1                run until blocked or complete (ALL remaining tasks)
  pwsh agent/run.ps1 -DryRun        print what would run, change nothing

Flags passed to claude, and why:
  --settings agent/harness-settings.json   deny rules + the Stop gate hook. These must NOT
                                           live in .claude/settings.json: the gate would trap
                                           interactive sessions and the deny rules would stop
                                           a human maintaining the harness.
  --permission-mode acceptEdits            `claude -p` starts in Manual mode on every plan,
                                           so an allowlist alone is not enough.
  --permission-prompts none                nobody is there to answer. Anything that would
                                           prompt is denied and Claude is told not to retry.
  --max-budget-usd                         per-task cost ceiling from state.json.

NEVER pass --bare. The headless docs recommend it for scripted calls and it is the single
most dangerous flag here: it skips hooks and CLAUDE.md, which means no gate and no
constitution.
#>
param(
    [string] $Only,
    [switch] $DryRun,
    [int]    $MaxAttempts = 3,
    # Stop after this many tasks complete. Without a bound, the bare `run.ps1` form runs
    # every remaining task in the project, which is rarely what you want interactively.
    [int]    $MaxTasks = 0
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo

$statePath    = Join-Path $repo 'agent/state.json'
$settingsPath = Join-Path $repo 'agent/harness-settings.json'
$logDir       = Join-Path $repo 'agent/log'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

function Read-State  { Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json }
function Write-State ($s) {
    $s | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $statePath -Encoding utf8
}
function Say ($m) { Write-Host "[run] $m" -ForegroundColor Cyan }
function Warn ($m) { Write-Host "[run] $m" -ForegroundColor Yellow }
function Die ($m) { Write-Host "[run] $m" -ForegroundColor Red; exit 1 }

try {
    # --- preflight --------------------------------------------------------------------
    foreach ($required in @($statePath, $settingsPath,
                            (Join-Path $repo 'agent/gate.ps1'),
                            (Join-Path $repo 'agent/tripwire.ps1'),
                            (Join-Path $repo 'CLAUDE.md'))) {
        if (-not (Test-Path $required)) { Die "missing $required" }
    }
    if (-not (Get-Command claude -ErrorAction SilentlyContinue)) { Die 'claude is not on PATH' }
    $headRef = (& git rev-parse --verify HEAD 2>$null)
    if (-not $headRef) { Die 'the repo has no commits - commit plans/ and docs/ first so task branches have a base' }

    $completed = 0

    while ($true) {
        if ($MaxTasks -gt 0 -and $completed -ge $MaxTasks) {
            Say "reached -MaxTasks $MaxTasks - stopping."
            exit 0
        }
        $state = Read-State

        # --- total budget circuit breaker ---------------------------------------------
        if ($state.budget.totalSpentUsd -ge $state.budget.totalCeilingUsd) {
            Die ("total budget ceiling reached: `$$($state.budget.totalSpentUsd) of " +
                 "`$$($state.budget.totalCeilingUsd). Raise budget.totalCeilingUsd to continue.")
        }

        # --- pick the next runnable task ----------------------------------------------
        $doneIds = @{}
        foreach ($t in $state.tasks) { if ($t.status -eq 'done') { $doneIds[$t.id] = $true } }

        $task = $null
        foreach ($t in $state.tasks) {
            if ($Only) { if ($t.id -eq $Only) { $task = $t; break } else { continue } }
            if ($t.status -ne 'pending') { continue }
            $ready = $true
            foreach ($d in $t.deps) { if (-not $doneIds.ContainsKey($d)) { $ready = $false; break } }
            if ($ready) { $task = $t; break }
        }

        if (-not $task) {
            if ($Only) { Die "task '$Only' not found in state.json" }
            $pending = @($state.tasks | Where-Object { $_.status -eq 'pending' })
            if ($pending.Count -eq 0) { Say 'all tasks complete.'; exit 0 }
            Die ("nothing runnable but $($pending.Count) task(s) still pending - " +
                 'a dependency is blocked. See agent/log/BLOCKED-*.md')
        }

        Say "task $($task.id) [$($task.model)/$($task.kind)] $($task.title)"
        Say "  branch $($task.branch)"
        if ($task.tests.Count -gt 0) { Say "  tests  $($task.tests -join ', ')" }

        if ($DryRun) { Say 'dry run - stopping here.'; exit 0 }

        if ($task.attempts -ge $MaxAttempts) {
            # Three consecutive failures halts the whole loop. Without this circuit breaker,
            # a single misunderstanding becomes an all-night thrash and a branch graveyard.
            $note = Join-Path $logDir "BLOCKED-$($task.id).md"
            @"
# BLOCKED: task $($task.id)

$($task.title)

Phase: $($task.phase)
Attempts: $($task.attempts)
Branch kept for inspection: $($task.branch)

Last gate output: agent/log/gate-last.txt
Last maven output: agent/log/mvn-last.txt
Transcript:        agent/log/$($task.id).jsonl

The loop halted here deliberately. Read the gate output, decide whether the task, the
phase spec or the harness is wrong, then reset this task's status to "pending" and its
attempts to 0 in agent/state.json.
"@ | Set-Content -LiteralPath $note -Encoding utf8
            $task.status = 'blocked'
            Write-State $state
            Die "task $($task.id) blocked after $($task.attempts) attempts. Wrote $note"
        }

        # --- branch from master -------------------------------------------------------
        & git checkout -q master 2>&1 | Out-Null
        $existing = (& git rev-parse --verify "refs/heads/$($task.branch)" 2>$null)
        if ($existing) {
            Say "  reusing existing branch (attempt $($task.attempts + 1))"
            & git checkout -q $task.branch 2>&1 | Out-Null
        } else {
            & git checkout -q -b $task.branch master 2>&1 | Out-Null
        }

        $task.status = 'in_progress'
        $state.current = $task.id
        Write-State $state

        # --- build the prompt ---------------------------------------------------------
        $designDetail = ''
        $phasePath = Join-Path $repo (Join-Path 'plans' $task.phase)
        if (Test-Path $phasePath) {
            $inBlock = $false
            $sb = New-Object System.Text.StringBuilder
            foreach ($line in (Get-Content -LiteralPath $phasePath)) {
                if ($line -match '^## Design detail') { $inBlock = $true; continue }
                if ($inBlock -and $line -match '^## ') { break }
                if ($inBlock) { [void] $sb.AppendLine($line) }
            }
            $designDetail = $sb.ToString()
        }

        $testLine = if ($task.tests.Count -gt 0) {
            "These test classes must exist and pass before you finish: $($task.tests -join ', '). " +
            "Their assertions are specified in the phase file's ## Tests table."
        } else {
            'This phase declares no named tests. The total test count still must not drop.'
        }

        $prompt = @"
Implement exactly one task from agent/state.json: task $($task.id).

$($task.title)

It comes from plans/$($task.phase). Read that file's ## Design detail and ## Tests sections
before writing anything, and read docs/spring-boot-4-notes.md before writing any Spring code.

$testLine

Do only this task. Do not start the next one. When you are done, commit your work with a
message explaining why, not just what.
"@

        $systemAppend = @"
You are working on task $($task.id) of the Revolut clone. The ## Design detail section of
plans/$($task.phase) follows. Treat it as the specification: the schemas, contracts and
algorithms in it are decided, and you implement them rather than redesigning them.

$designDetail
"@

        $appendFile = Join-Path $logDir "$($task.id).system.txt"
        $systemAppend | Set-Content -LiteralPath $appendFile -Encoding utf8

        # --- run one fresh claude process ---------------------------------------------
        $transcript = Join-Path $logDir "$($task.id).jsonl"
        $ceiling = $state.budget.perTaskCeilingUsd

        Say "  launching claude -p (model $($task.model), ceiling `$$ceiling)"
        $claudeArgs = @(
            '-p', $prompt,
            '--model', $task.model,
            '--append-system-prompt-file', $appendFile,
            '--settings', $settingsPath,
            '--permission-mode', 'acceptEdits',
            '--permission-prompts', 'none',
            '--max-budget-usd', $ceiling,
            '--output-format', 'stream-json',
            '--verbose'
        )

        & claude @claudeArgs *>&1 | Tee-Object -FilePath $transcript -Append | Out-Null
        $claudeExit = $LASTEXITCODE

        # SIGTERM leaves the turn unfinished rather than failing it, so it is retryable
        # and should not burn an attempt.
        if ($claudeExit -eq 143) {
            Warn "  claude was terminated (exit 143) - retrying without counting an attempt"
            continue
        }

        # --- recover the cost from the transcript's result message ---------------------
        $taskCost = 0.0
        if (Test-Path $transcript) {
            foreach ($line in (Get-Content -LiteralPath $transcript -Tail 40)) {
                if ($line -notmatch '"type"\s*:\s*"result"') { continue }
                try {
                    $r = $line | ConvertFrom-Json
                    if ($r.PSObject.Properties.Name -contains 'total_cost_usd') {
                        $taskCost = [double] $r.total_cost_usd
                    }
                } catch { }
            }
        }

        # --- the gate is the only authority on done -----------------------------------
        $gateOut = & pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'agent/gate.ps1') `
                        -TaskId $task.id 2>&1 | Out-String
        $gateExit = $LASTEXITCODE
        $gateOut | Set-Content -LiteralPath (Join-Path $logDir "gate-$($task.id).txt") -Encoding utf8
        Write-Output $gateOut

        $state = Read-State
        $task = $state.tasks | Where-Object { $_.id -eq $task.id } | Select-Object -First 1
        $task.costUsd = [double] $task.costUsd + $taskCost
        $state.budget.totalSpentUsd = [double] $state.budget.totalSpentUsd + $taskCost

        if ($gateExit -eq 0) {
            # Commit anything the agent left uncommitted, including untracked files.
            $dirty = (& git status --porcelain 2>$null | Out-String).Trim()
            if ($dirty) {
                & git add -A 2>&1 | Out-Null
                & git commit -q -m "task $($task.id): $($task.title)" 2>&1 | Out-Null
            }
            $sha = (& git rev-parse --short HEAD 2>$null)
            & git checkout -q master 2>&1 | Out-Null
            & git merge --ff-only $task.branch 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) {
                Warn "  fast-forward merge failed - master moved. Branch $($task.branch) kept."
                $task.attempts = [int] $task.attempts + 1
                $task.status = 'pending'
                Write-State $state
                continue
            }
            & git tag "task/$($task.id)" 2>&1 | Out-Null
            $task.status = 'done'
            $task.commit = $sha
            Write-State $state
            $completed++
            Say ("  GREEN - merged $sha, tagged task/$($task.id), cost " +
                 "`$$([math]::Round($taskCost,4)), total `$$([math]::Round($state.budget.totalSpentUsd,2))")
            if ($Only) { exit 0 }
        } else {
            $task.attempts = [int] $task.attempts + 1
            $task.status = 'pending'
            Write-State $state
            Warn "  RED - attempt $($task.attempts) of $MaxAttempts. Branch kept for inspection."
            if ($Only) { exit 1 }
        }
    }
}
finally { Pop-Location }
