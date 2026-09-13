#!/usr/bin/env pwsh
# PreToolUse hook on Bash: defence in depth behind the denylist.
#
# The permissions deny rules in agent/harness-settings.json are the primary guard, and
# a Write deny also stops a Bash command writing that path. This hook catches the shapes
# a permission rule cannot express: a destructive flag buried mid-command, a redirect into
# a protected file, a pipeline that rewrites the gate.
#
# Output is the nested hookSpecificOutput form with permissionDecision, not the deprecated
# flat "decision" field.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$raw = [Console]::In.ReadToEnd()
$command = ''
try {
    if ($raw -and $raw.Trim()) {
        $payload = $raw | ConvertFrom-Json
        if ($payload.PSObject.Properties.Name -contains 'tool_input' -and $payload.tool_input) {
            if ($payload.tool_input.PSObject.Properties.Name -contains 'command') {
                $command = [string]$payload.tool_input.command
            }
        }
    }
} catch {
    # Unparseable payload: stay out of the way rather than blocking every command.
    exit 0
}

if (-not $command) { exit 0 }

# Each rule is a regex plus the reason reported back to the agent.
$rules = @(
    @{ Pattern = 'git\s+push\s+.*(--force|-f)\b';            Reason = 'force push' }
    @{ Pattern = 'git\s+reset\s+--hard';                     Reason = 'git reset --hard' }
    @{ Pattern = 'git\s+clean\s+-[a-z]*f';                   Reason = 'git clean -f' }
    @{ Pattern = 'git\s+(rebase|filter-branch)\b';           Reason = 'history rewrite' }
    @{ Pattern = 'docker\s+(system|volume|image)\s+prune';   Reason = 'docker prune' }
    @{ Pattern = 'docker\s+volume\s+rm\b';                   Reason = 'docker volume rm' }
    @{ Pattern = 'rm\s+-[a-z]*r[a-z]*f|rm\s+-[a-z]*f[a-z]*r'; Reason = 'rm -rf' }
    @{ Pattern = 'Remove-Item\s+.*-Recurse.*-Force';         Reason = 'Remove-Item -Recurse -Force' }
    # Writes into the specification or the gate, by redirect, tee, sed -i or cp/mv.
    @{ Pattern = '(>|>>|\btee\b|\bsed\s+-i\b|\bcp\b|\bmv\b|Set-Content|Out-File|Add-Content).*(agent/(gate|tripwire|run)\.ps1|agent/harness-settings\.json|\.claude/|plans/|CLAUDE\.md|docs/architecture\.md)';
       Reason = 'write into the specification, the harness config, or the gate' }
    # Test suppression smuggled in through a shell command rather than an edit.
    @{ Pattern = '(>|>>|\btee\b|\bsed\s+-i\b|Set-Content|Out-File).*@Disabled';
       Reason = 'test suppression via shell write' }
    @{ Pattern = '\bfind\b.*-name\s+.*Test\.java.*-delete';  Reason = 'bulk test deletion' }
    @{ Pattern = 'git\s+rm\s+.*Test\.java';                  Reason = 'test deletion via git rm' }
    @{ Pattern = 'mvn\s+.*-DskipTests';                      Reason = 'mvn -DskipTests' }
    @{ Pattern = 'mvn\s+.*-Dmaven\.test\.skip';              Reason = 'mvn -Dmaven.test.skip' }
    @{ Pattern = 'mvn\s+.*-Dsurefire\.failIfNoSpecifiedTests'; Reason = 'surefire test-selection override' }
)

foreach ($rule in $rules) {
    if ($command -match $rule.Pattern) {
        @{
            hookSpecificOutput = @{
                hookEventName            = 'PreToolUse'
                permissionDecision       = 'deny'
                permissionDecisionReason = "Blocked by the harness denylist ($($rule.Reason)). This is not negotiable and the guard itself is permission-denied -- solve the underlying problem instead."
            }
        } | ConvertTo-Json -Depth 5 -Compress | Write-Output
        exit 0
    }
}

exit 0
