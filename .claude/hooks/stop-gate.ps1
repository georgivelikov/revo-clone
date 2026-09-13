#!/usr/bin/env pwsh
# Stop hook: the mechanism that makes unattended merge safe.
#
# Runs agent/gate.ps1 and, while the gate is red, returns decision:"block" so the
# agent cannot end its turn on a broken build. This converts "please make sure the
# tests pass" from a request into a property of the runtime.
#
# Loaded only via agent/harness-settings.json (--settings), never from
# .claude/settings.json -- in project settings it would trap interactive sessions too.
#
# Block payload shape is the nested hookSpecificOutput form required by current
# Claude Code. The older flat {"decision","reason"} form is NOT used.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Ceiling on consecutive blocks. Blocking forever would burn the whole context
# window on a task that cannot be fixed; relenting lets run.ps1 record a failed
# attempt and its three-strikes circuit breaker take over.
$MaxBlocks = 6

$projectDir = if ($env:CLAUDE_PROJECT_DIR) { $env:CLAUDE_PROJECT_DIR } else { (Get-Location).Path }
$logDir     = Join-Path $projectDir 'agent/log'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

# --- read the hook payload from stdin -------------------------------------------------
$raw = [Console]::In.ReadToEnd()
$sessionId = 'unknown'
try {
    if ($raw -and $raw.Trim()) {
        $payload = $raw | ConvertFrom-Json
        if ($payload.PSObject.Properties.Name -contains 'session_id' -and $payload.session_id) {
            $sessionId = [string]$payload.session_id
        }
    }
} catch {
    # A malformed payload must not take the gate offline. Carry on with defaults.
}

$counterFile = Join-Path $logDir ("stop-blocks-$sessionId.txt")

# --- run the gate ---------------------------------------------------------------------
$gate = Join-Path $projectDir 'agent/gate.ps1'
if (-not (Test-Path $gate)) {
    # No gate installed yet. Say so loudly rather than silently allowing the stop.
    @{ systemMessage = "WARNING: agent/gate.ps1 not found - this turn was NOT gated." } |
        ConvertTo-Json -Compress | Write-Output
    exit 0
}

$gateLog = Join-Path $logDir 'gate-last.txt'
$output  = & pwsh -NoProfile -ExecutionPolicy Bypass -File $gate 2>&1 | Out-String
$gateExit = $LASTEXITCODE
$output | Set-Content -LiteralPath $gateLog -Encoding utf8

if ($gateExit -eq 0) {
    Remove-Item -LiteralPath $counterFile -ErrorAction SilentlyContinue
    @{ systemMessage = 'Gate green.'; suppressOutput = $true } |
        ConvertTo-Json -Compress | Write-Output
    exit 0
}

# --- gate is red: block, up to the ceiling --------------------------------------------
$blocks = 0
if (Test-Path $counterFile) {
    $parsed = 0
    if ([int]::TryParse((Get-Content -LiteralPath $counterFile -Raw).Trim(), [ref]$parsed)) { $blocks = $parsed }
}
$blocks++
Set-Content -LiteralPath $counterFile -Value $blocks -Encoding utf8

if ($blocks -gt $MaxBlocks) {
    Remove-Item -LiteralPath $counterFile -ErrorAction SilentlyContinue
    @{
        systemMessage = "Gate still red after $MaxBlocks blocked stops. Releasing the turn so the driver can record a failed attempt. See agent/log/gate-last.txt."
    } | ConvertTo-Json -Compress | Write-Output
    exit 0
}

$reason = @"
The build gate is RED, so this turn cannot end. Fix the cause and try again.
(blocked stop $blocks of $MaxBlocks)

Do NOT weaken, delete, @Disabled or assumeTrue any test to get past this, and do not
edit agent/gate.ps1 or agent/tripwire.ps1 - both are permission-denied. If a test is
genuinely wrong, fix the test and say so in the commit body.

--- gate.ps1 output (exit $gateExit) ---
$output
"@

@{
    hookSpecificOutput = @{
        hookEventName = 'Stop'
        decision      = 'block'
        reason        = $reason
    }
} | ConvertTo-Json -Depth 5 -Compress | Write-Output
exit 0
