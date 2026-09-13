#!/usr/bin/env pwsh
# Forbidden-pattern scan over the branch diff. Exit 0 = clean, 1 = at least one hit.
#
# Called by agent/gate.ps1. Any hit fails the gate with the offending file:line.
# This file is permission-denied to the agent and is the harness's accumulated scar
# tissue: every time a run goes wrong in a new way, a rule gets added here.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
try {
    # --- work out what to scan --------------------------------------------------------
    # Committed work on this branch plus anything still uncommitted, against the point
    # the branch left master.
    $base = $null
    try { $base = (& git merge-base master HEAD 2>$null | Select-Object -First 1) } catch { }
    if ($base) { $diffArgs = @('diff', $base) } else { $diffArgs = @('diff', 'HEAD') }

    $diff = & git @diffArgs 2>$null | Out-String
    $findings = New-Object System.Collections.Generic.List[string]

    # Untracked files appear in NO git diff, so an agent could write a violating file,
    # never `git add` it, and still have Maven compile it. Synthesise diff hunks for them
    # so every line counts as added.
    $untracked = @(& git ls-files --others --exclude-standard 2>$null)
    $synthetic = New-Object System.Text.StringBuilder
    foreach ($u in $untracked) {
        if (-not $u) { continue }
        if ($u -match '[\/]target[\/]|[\/]node_modules[\/]|\.class$|\.jar$') { continue }
        if (-not (Test-Path -LiteralPath $u -PathType Leaf)) { continue }
        $item = Get-Item -LiteralPath $u
        if ($item.Length -gt 512KB) { continue }
        [void] $synthetic.AppendLine("+++ b/$u")
        [void] $synthetic.AppendLine('@@ -0,0 +1 @@')
        foreach ($l in (Get-Content -LiteralPath $u -ErrorAction SilentlyContinue)) {
            [void] $synthetic.AppendLine("+$l")
        }
    }
    if ($synthetic.Length -gt 0) { $diff = $diff + "`n" + $synthetic.ToString() }

    # --- 1. deleted or renamed test files --------------------------------------------
    $nameStatus = & git @($diffArgs + '--name-status') 2>$null
    foreach ($line in $nameStatus) {
        if ($line -match '^(D|R\d*)\s+(.*Test\.java)') {
            $findings.Add("$($Matches[2]): test file deleted or renamed (D/R). Tests may be fixed, never removed.")
        }
    }

    # --- 2. line-level rules over ADDED lines only ------------------------------------
    # Rules carry an optional PathAllow: a regex of paths where the pattern is legitimate.
    $rules = @(
        @{ Pattern = '@Disabled\b';            Reason = 'test suppression (@Disabled)' }
        @{ Pattern = '@Ignore\b';              Reason = 'test suppression (@Ignore)' }
        @{ Pattern = '\bassumeTrue\s*\(';      Reason = 'test suppression (assumeTrue)' }
        @{ Pattern = '\bassumeThat\s*\(';      Reason = 'test suppression (assumeThat)' }
        @{ Pattern = '\bassumingThat\s*\(';    Reason = 'test suppression (assumingThat)' }

        # Money rule. double/float are banned outright in Java sources; BigDecimal is
        # permitted only in FxMath and in a toMajor conversion.
        @{ Pattern = '^\s*(private|public|protected)?\s*(static\s+)?(final\s+)?(double|float)\s+\w+';
           Reason = 'double/float field - money is always long minor units'; Ext = '\.java$' }
        @{ Pattern = '\b(double|float)\s+(amount|total|balance|price|rate|fee|sum)\w*';
           Reason = 'double/float money variable - money is always long minor units'; Ext = '\.java$' }
        @{ Pattern = '\bBigDecimal\b';
           Reason = 'BigDecimal outside FxMath/toMajor'; Ext = '\.java$';
           PathAllow = '[\/](FxMath|Money)\.java$|[\/]FxMathTest|[\/]MoneyParsingTest' }

        # Jackson 3 is the Boot 4.1 default; Jackson 2 imports are a migration mistake.
        @{ Pattern = 'com\.fasterxml\.jackson';
           Reason = 'Jackson 2 import - Boot 4.1 defaults to Jackson 3 (tools.jackson). See docs/spring-boot-4-notes.md' }

        # Coordinates that changed in this stack and are commonly mis-remembered.
        @{ Pattern = '<artifactId>spring-cloud-starter-gateway</artifactId>';
           Reason = 'renamed: use spring-cloud-starter-gateway-server-webmvc (or -webflux)' }

        # Schema ownership and config hygiene.
        @{ Pattern = 'ddl-auto:\s*(update|create|create-drop)';
           Reason = 'Flyway owns all schema; ddl-auto must not exceed validate' }
        @{ Pattern = 'ddl-auto=\s*(update|create|create-drop)';
           Reason = 'Flyway owns all schema; ddl-auto must not exceed validate' }

        # The outbox is the only route for cross-service writes.
        @{ Pattern = 'kafkaTemplate\.send|KafkaTemplate\.send';
           Reason = 'direct KafkaTemplate.send - cross-service writes go through the outbox';
           PathAllow = 'common-messaging[\/]spring[\/]' }

        # Destructive commands in committed scripts.
        @{ Pattern = 'push\s+(--force|-f)\b';        Reason = 'force push in a committed script' }
        @{ Pattern = 'reset\s+--hard';               Reason = 'reset --hard in a committed script' }
        @{ Pattern = 'docker\s+system\s+prune';      Reason = 'docker system prune in a committed script' }
        @{ Pattern = 'rm\s+-rf\b';                   Reason = 'rm -rf in a committed script' }

        # Swallowed failures.
        @{ Pattern = 'catch\s*\(\s*\w*Exception\s+\w+\s*\)\s*\{\s*\}';
           Reason = 'empty catch block - swallowed failure'; Ext = '\.java$' }
    )

    # Walk the diff, tracking the current file so findings can name file:line.
    # The specification and the harness describe the forbidden patterns in prose, so
    # scanning them produces nothing but false positives. They are permission-denied to
    # the agent, so excluding them costs no coverage.
    $skipPaths = '^(plans/|docs/architecture\.md|docs/spring-boot-4-notes\.md|CLAUDE\.md|agent/(PLAN\.md|gate\.ps1|tripwire\.ps1|run\.ps1|harness-settings\.json)|\.claude/)'

    $currentFile = ''
    $newLineNo   = 0
    foreach ($line in ($diff -split "`r?`n")) {
        if ($line -match '^\+\+\+ b/(.*)$')      { $currentFile = $Matches[1]; continue }
        if ($line -match '^@@ .*\+(\d+)')        { $newLineNo = [int]$Matches[1] - 1; continue }
        if ($line.StartsWith('---') -or $line.StartsWith('diff ') -or
            $line.StartsWith('index ') -or $line.StartsWith('new file') -or
            $line.StartsWith('deleted file') -or $line.StartsWith('similarity') -or
            $line.StartsWith('rename ')) { continue }

        if ($line.StartsWith('+')) {
            $newLineNo++
            if ($currentFile -match $skipPaths) { continue }
            $text = $line.Substring(1)

            # `localhost` is allowed only inside a local profile or a comment in yaml.
            if ($text -match 'localhost' -and $currentFile -match '\.(ya?ml|properties)$' -and
                $currentFile -notmatch 'local' -and $text -notmatch '^\s*#') {
                $findings.Add("${currentFile}:${newLineNo}: localhost outside a local profile - endpoints come from environment variables")
            }

            foreach ($rule in $rules) {
                if ($rule.ContainsKey('Ext')   -and $currentFile -notmatch $rule.Ext)       { continue }
                if ($rule.ContainsKey('PathAllow') -and $currentFile -match $rule.PathAllow) { continue }
                if ($text -match $rule.Pattern) {
                    $findings.Add("${currentFile}:${newLineNo}: $($rule.Reason)")
                }
            }
        }
        elseif (-not $line.StartsWith('-')) {
            $newLineNo++
        }
    }

    # --- 3. Testcontainers 2.x module names, checked per dependency --------------------
    # This has to be a file-level check: a bare <artifactId> line carries no groupId, so a
    # line-level rule cannot tell org.testcontainers:postgresql (wrong) from
    # org.postgresql:postgresql (the JDBC driver, correct).
    $poms = @(& git ls-files 'pom.xml' '**/pom.xml' 2>$null) +
            @(& git ls-files --others --exclude-standard 2>$null | Where-Object { $_ -match 'pom\.xml$' })
    foreach ($pom in ($poms | Sort-Object -Unique)) {
        if (-not $pom -or -not (Test-Path -LiteralPath $pom)) { continue }
        try { [xml] $doc = Get-Content -LiteralPath $pom -Raw } catch { continue }
        foreach ($dep in $doc.GetElementsByTagName('dependency')) {
            if ($dep.groupId -ne 'org.testcontainers') { continue }
            $aid = [string] $dep.artifactId
            if ($aid -and $aid -ne 'testcontainers' -and -not $aid.StartsWith('testcontainers-')) {
                $findings.Add("${pom}: org.testcontainers:$aid - Testcontainers 2.x prefixes modules, use testcontainers-$aid")
            }
        }
    }

    # --- report -----------------------------------------------------------------------
    if ($findings.Count -gt 0) {
        Write-Output "TRIPWIRE: $($findings.Count) violation(s)"
        $findings | Sort-Object -Unique | ForEach-Object { Write-Output "  $_" }
        exit 1
    }

    Write-Output 'TRIPWIRE: clean'
    exit 0
}
finally { Pop-Location }
