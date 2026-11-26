# Free gate 1 of the v2 plan: exercises bench-v2.ps1 against MockOpenAi.java, including failure paths, and checks that
# no step makes a model request it should not. Requires Docker, a built runner, and an agent from build-agent-v2.ps1.
#   check-harness.ps1 -AgentDir <agent> -Work <empty directory>
param([Parameter(Mandatory)][string]$AgentDir, [Parameter(Mandatory)][string]$Work)
$ErrorActionPreference = 'Continue'
$bench = Join-Path $PSScriptRoot 'bench-v2.ps1'
$mockSource = Join-Path $PSScriptRoot 'MockOpenAi.java'
if (Test-Path $Work) { throw "$Work exists; use a new directory." }
New-Item -ItemType Directory -Path $Work | Out-Null
$Work = (Resolve-Path $Work).Path
$failures = New-Object System.Collections.ArrayList
function Check($name, [bool]$ok, $detail = '') {
    if ($ok) { "PASS $name" } else { "FAIL $name $detail"; [void]$failures.Add($name) }
}

$port = 18081
$mock = $null
function Start-Mock($behavior) {
    if ($script:mock) { Stop-Process -Id $script:mock.Id -Force -ErrorAction SilentlyContinue; $script:mock.WaitForExit(10000) | Out-Null }
    $log = Join-Path $Work "mock-$behavior.log"
    $script:mockLog = $log
    $script:mock = Start-Process java -ArgumentList $mockSource, $port, $behavior -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    for ($t = 0; $t -lt 30; $t++) { if ((Get-Content $log -Raw -ErrorAction SilentlyContinue) -match 'listening') { return }; Start-Sleep 1 }
    throw 'mock did not start'
}
function Get-Served { @(Get-Content $script:mockLog -ErrorAction SilentlyContinue | Where-Object { $_ -match '^served ' }).Count }
function Run-Bench($runDir, [string[]]$extra) {
    $arguments = @('-Model', 'gpt-6-luna', '-AgentDir', $AgentDir, '-Out', $runDir, '-Replicates', '1',
        '-Modes', 'NEUTRAL,HARDENED', '-Scenarios', 'sj-101,sj-103,sj-004-duplicate-delivery',
        '-InputPrice', '0.10', '-OutputPrice', '0.50', '-CostLimit', '1',
        '-OpenAiBaseUrl', "http://127.0.0.1:$port/v1/") + $extra
    $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $bench @arguments 2>&1
    $script:lastExit = $LASTEXITCODE
    $output | Add-Content (Join-Path $Work 'bench-output.log')
}
function Final($runDir, $trialPattern) {
    $file = Get-ChildItem (Join-Path $runDir 'trials') -Directory | Where-Object Name -like $trialPattern | Select-Object -First 1
    Get-Content (Join-Path $file.FullName 'final.json') -Raw | ConvertFrom-Json
}

try {
    # 1. A normal run completes; one request per ticket.
    Start-Mock 'normal'
    $run = Join-Path $Work 'spend\normal'
    New-Item -ItemType Directory -Force -Path (Split-Path $run) | Out-Null
    Run-Bench $run @()
    $finals = @(Get-ChildItem (Join-Path $run 'trials') -Recurse -Filter final.json | ForEach-Object { (Get-Content $_.FullName -Raw | ConvertFrom-Json).outcome })
    $expected = 2 * (2 + 2 + 2)   # two modes; sj-101 and sj-103 have two tickets each, sj-004 two deliveries
    Check 'normal run completes every trial' (($finals -eq 'completed').Count -eq 6 -and $lastExit -eq 0) "finals: $($finals -join ',') exit $lastExit"
    Check 'one model request per ticket' ((Get-Served) -eq $expected) "served $(Get-Served), expected $expected"

    # 2. Rerunning a finished run makes no request.
    $before = Get-Served
    Run-Bench $run @()
    Check 'rerun of a finished run makes no request' ((Get-Served) -eq $before)

    # 3. Crash after the attempt status was written, before final.json: resumes as completed, no request.
    $trial = Get-ChildItem (Join-Path $run 'trials') -Directory | Select-Object -First 1
    Remove-Item (Join-Path $trial.FullName 'final.json')
    Run-Bench $run @()
    $final = Get-Content (Join-Path $trial.FullName 'final.json') -Raw | ConvertFrom-Json
    Check 'crash after status: completed, no new request' ($final.outcome -eq 'completed' -and (Get-Served) -eq $before -and
        !(Test-Path (Join-Path $trial.FullName 'attempt-2'))) "outcome $($final.outcome)"

    # 4. Crash before the status was written: final 'unknown', not repeated, no request.
    $trial = Get-ChildItem (Join-Path $run 'trials') -Directory | Select-Object -Last 1
    Remove-Item (Join-Path $trial.FullName 'final.json'), (Join-Path $trial.FullName 'attempt-1\status.json')
    Run-Bench $run @()
    $final = Get-Content (Join-Path $trial.FullName 'final.json') -Raw | ConvertFrom-Json
    Check 'crash before status: unknown, not repeated' ($final.outcome -eq 'unknown' -and (Get-Served) -eq $before -and
        !(Test-Path (Join-Path $trial.FullName 'attempt-2'))) "outcome $($final.outcome)"

    # 5. Resuming with different settings is refused before any request.
    Run-Bench $run @('-Seed', '1')
    Check 'changed settings are refused' ($lastExit -ne 0 -and (Get-Served) -eq $before)

    # 6. Wrong served model stops the run after the first attempt.
    Start-Mock 'wrong-model'
    $run = Join-Path $Work 'spend\wrong-model'
    Run-Bench $run @()
    $stopped = @(Get-ChildItem (Join-Path $run 'trials') -Recurse -Filter final.json | ForEach-Object { (Get-Content $_.FullName -Raw | ConvertFrom-Json).outcome })
    Check 'wrong served model stops the run' ($lastExit -eq 4 -and $stopped.Count -eq 1 -and $stopped[0] -eq 'stopped') "exit $lastExit, finals $($stopped -join ',')"

    # 6b. A stopped run stays stopped: resuming it with a healthy endpoint makes no request.
    Start-Mock 'normal'
    Run-Bench $run @()
    Check 'stopped run refuses to resume' ($lastExit -eq 4 -and (Get-Served) -eq 0) "exit $lastExit, served $(Get-Served)"

    # 6c. A provider error (here HTTP 500) halts the run; the harness does not retry it. The client itself retries once.
    Start-Mock 'server-error'
    $run = Join-Path $Work 'spend\server-error'
    Run-Bench $run @()
    $finals = @(Get-ChildItem (Join-Path $run 'trials') -Recurse -Filter final.json | ForEach-Object { (Get-Content $_.FullName -Raw | ConvertFrom-Json).outcome })
    # Every trial here has two tickets, and the client sends each failed request twice: 4 requests, one trial.
    Check 'provider error halts the run' ($lastExit -eq 5 -and $finals.Count -eq 1 -and $finals[0] -eq 'provider-error' -and
        (Get-Served) -eq 4) "exit $lastExit, finals $($finals -join ','), served $(Get-Served)"

    # 6d. An HTTP 429 is classified as a rate limit (retried with backoff), not as a provider error.
    Start-Mock 'rate-limit'
    $run = Join-Path $Work 'spend\rate-limit'
    Run-Bench $run @('-MaxAttempts', '2', '-MaxTrials', '1')
    $status = Get-ChildItem (Join-Path $run 'trials') -Recurse -Filter status.json | Select-Object -First 1 | ForEach-Object { Get-Content $_.FullName -Raw | ConvertFrom-Json }
    Check 'HTTP 429 is classified as a rate limit' ($status.outcome -eq 'rate-limited' -and $lastExit -eq 6) "outcome $($status.outcome) ($($status.reason)), exit $lastExit"

    # 7. A response without usage stops the run.
    Start-Mock 'no-usage'
    $run = Join-Path $Work 'spend\no-usage'
    Run-Bench $run @()
    Check 'response without usage stops the run' ($lastExit -eq 4) "exit $lastExit"

    # 8. The cost limit stops before any request; it counts the earlier runs under the same parent.
    Start-Mock 'normal'
    $run = Join-Path $Work 'spend\over-limit'
    $arguments = @('-CostLimit', '0.0001')
    $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $bench -Model gpt-6-luna -AgentDir $AgentDir -Out $run `
        -Replicates 1 -Modes NEUTRAL -Scenarios sj-101 -InputPrice 0.10 -OutputPrice 0.50 -CostLimit 0.0001 `
        -OpenAiBaseUrl "http://127.0.0.1:$port/v1/" 2>&1
    $limitExit = $LASTEXITCODE
    $output | Add-Content (Join-Path $Work 'bench-output.log')
    Check 'cost limit stops before any request' ($limitExit -eq 3 -and (Get-Served) -eq 0) "exit $limitExit, served $(Get-Served)"

    # 9. Scoring and analysis of the normal run.
    $score = & (Join-Path $PSScriptRoot 'score-v2.ps1') -Run (Join-Path $Work 'spend\normal') 2>&1
    $rows = @(Import-Csv (Join-Path $Work 'spend\normal\scores.csv'))
    $completed = @($rows | Where-Object outcome -eq 'completed')
    Check 'scores have the same columns on every row' ($rows.Count -eq 6 -and ($rows[0].PSObject.Properties.Name -contains 'recommended'))
    Check 'completed trials have valid evidence' (@($completed | Where-Object evidence -ne 'valid').Count -eq 0) (($completed | ForEach-Object { "$($_.trial)=$($_.evidence)" }) -join ' ')
    # A PowerShell script sets no exit code, so success is "no error thrown" (not $LASTEXITCODE, which still holds
    # the last native command's code).
    $analysisError = $null
    try { $analysis = & (Join-Path $PSScriptRoot 'analyze-v2.ps1') -Scores (Join-Path $Work 'spend\normal\scores.csv') } catch { $analysisError = $_ }
    Check 'analysis runs' (!$analysisError -and ($analysis -join "`n") -match '## Calibration') "$analysisError"
} finally {
    if ($mock) { Stop-Process -Id $mock.Id -Force -ErrorAction SilentlyContinue }
}
if ($failures.Count) { throw "$($failures.Count) harness check(s) failed; see $Work" }
'All harness checks passed.'
