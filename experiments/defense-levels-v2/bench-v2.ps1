# Runs the defense-levels v2 benchmark for one model. See docs/benchmark-v2-plan.md.
#
# Every trial (one mode, one scenario, one replicate) gets a fresh Kafka broker and a fresh agent process, so no
# memory, deduplication state, or leftover records carry over. Trials follow a frozen manifest; an interrupted run
# resumes from the recorded attempt outcomes and never repeats a completed or possibly billed attempt. Only attempts
# that made no model call, or that the provider explicitly rate-limited, are retried.
#
# Spend fails closed: every run directory under the parent of -Out counts, an attempt whose cost is unclear counts at
# -TrialCostReserve, and the run stops before a trial that could cross -CostLimit. After each attempt the served model
# and the number of model calls are checked; a mismatch stops the run.
#
# Disposable local Kafka only. Requires the agent built with build-agent-v2.ps1 and OPENAI_API_KEY in the environment
# (not needed with -OpenAiBaseUrl, which targets MockOpenAi.java). The key is never written by this script.
param(
    [Parameter(Mandatory)][string]$Model,
    [Parameter(Mandatory)][string]$AgentDir,
    [Parameter(Mandatory)][string]$Out,
    [int]$Replicates = 10,
    [string[]]$Modes = @('NEUTRAL', 'PROMPT_ONLY', 'HARDENED'),
    # Optional subset of scenario names (file names without .yaml), for pilots and checks.
    [string[]]$Scenarios,
    # Prices in US dollars per million tokens, from the provider's pricing page.
    [Parameter(Mandatory)][double]$InputPrice,
    [Parameter(Mandatory)][double]$OutputPrice,
    # Total spend allowed across every run directory under the parent of -Out (pilots and retries included).
    [Parameter(Mandatory)][double]$CostLimit,
    # Worst-case cost of one trial; used before each trial and for attempts whose cost is unclear. The default covers
    # gpt-6.2-sol: up to 3 tickets x 2 client dispatches x (about 2,000 input + 1,500 output tokens) is about $0.11.
    [double]$TrialCostReserve = 0.12,
    [int]$MaxAttempts = 3,
    # Stop after this many trials in this invocation (0 = no limit); use 1 for the first paid trial.
    [int]$MaxTrials = 0,
    [int]$Seed = 20261126,
    # For free end-to-end checks against MockOpenAi.java; loopback addresses only.
    [string]$OpenAiBaseUrl,
    # A file outside the repository that contains only the API key, used when OPENAI_API_KEY is not set.
    # The key is passed to the agent through its environment and never printed or written.
    [string]$ApiKeyFile = (Join-Path $env:USERPROFILE '.streamjack\openai-api-key.txt')
)
$ErrorActionPreference = 'Continue'
# Lists arrive as one comma-separated string when the script is started with powershell -File.
$Modes = @($Modes | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
if ($Scenarios) { $Scenarios = @($Scenarios | ForEach-Object { $_ -split ',' } | Where-Object { $_ }) }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runner = Join-Path $root 'streamjack-kafka\target\streamjack-kafka-0.3.0-SNAPSHOT-all.jar'
$agentJar = Join-Path $AgentDir 'quarkus-run.jar'
foreach ($required in $runner, $agentJar) { if (!(Test-Path $required)) { throw "Not found: $required" } }

$savedEnvironment = @{}
foreach ($name in 'OPENAI_API_KEY', 'OPENAI_BASE_URL', 'OPENAI_MODEL', 'KAFKA_BOOTSTRAP_SERVERS', 'STREAMJACK_AGENT_MODEL',
        'STREAMJACK_AGENT_SECURITY_MODE', 'STREAMJACK_USAGE_LOG') {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
# Runtime overrides would change the experiment without changing the fingerprinted build (for example the output
# cap or any Quarkus setting), so the agent starts without them. They are restored when the script ends.
foreach ($variable in Get-ChildItem Env: | Where-Object { $_.Name -match '^(QUARKUS_|MP_|SMALLRYE_|STREAMJACK_LLM_PROVIDER$|OPENAI_MAX_COMPLETION_TOKENS$)' }) {
    $savedEnvironment[$variable.Name] = $variable.Value
    Remove-Item "Env:$($variable.Name)"
}
if ($OpenAiBaseUrl) {
    if ($OpenAiBaseUrl -notmatch '^http://127\.0\.0\.1:\d+/') { throw '-OpenAiBaseUrl is only for a local mock on 127.0.0.1.' }
    $endpoint = 'mock'
    $env:OPENAI_BASE_URL = $OpenAiBaseUrl
    # Never send a real key to the mock.
    $env:OPENAI_API_KEY = 'mock-key-not-real'
} else {
    $endpoint = 'openai'
    Remove-Item Env:OPENAI_BASE_URL -ErrorAction SilentlyContinue
    if (!$env:OPENAI_API_KEY -and (Test-Path $ApiKeyFile)) {
        if ((Resolve-Path $ApiKeyFile).Path.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'The API key file must be outside the repository.'
        }
        $env:OPENAI_API_KEY = (Get-Content $ApiKeyFile -Raw).Trim()
    }
    if (!$env:OPENAI_API_KEY) { throw "Set OPENAI_API_KEY, or put the key in $ApiKeyFile." }
}

$scenarioFiles = @(Get-ChildItem (Join-Path $PSScriptRoot 'scenarios') -Filter '*.yaml' | Sort-Object Name | ForEach-Object FullName) +
    (Join-Path $root 'scenarios\sj-004-duplicate-delivery.yaml')
if ($Scenarios) {
    $scenarioFiles = @($scenarioFiles | Where-Object { $Scenarios -contains [IO.Path]::GetFileNameWithoutExtension($_) })
    if ($scenarioFiles.Count -ne $Scenarios.Count) { throw 'Unknown scenario name in -Scenarios.' }
}
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Out = (Resolve-Path $Out).Path
$spendRoot = Split-Path $Out -Parent

function Write-Json($object, $path) {
    # Write to a temporary file, then rename, so a crash never leaves a half-written status.
    # State files must never fail silently: a lost status could let a billed attempt be repeated.
    $temporary = "$path.tmp"
    $object | ConvertTo-Json -Depth 6 | Set-Content -Path $temporary -Encoding ASCII -ErrorAction Stop
    Move-Item -Path $temporary -Destination $path -Force -ErrorAction Stop
}
function Read-Outcome($path) {
    # A state file that exists but cannot be read, or has no outcome, stops the run instead of being skipped.
    $state = Get-Content $path -Raw -ErrorAction Stop | ConvertFrom-Json
    if (!$state -or !$state.outcome) { throw "Unreadable state file: $path" }
    return $state
}
function Get-Fingerprint([string[]]$paths) {
    $hashes = foreach ($path in $paths) { (Get-FileHash $path -Algorithm SHA256).Hash }
    $bytes = [Text.Encoding]::ASCII.GetBytes(($hashes -join ''))
    [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($bytes)).Replace('-', '')
}

# --- Frozen settings and manifest -----------------------------------------------------------------------------------
# Resuming is refused if anything that shapes the results changed: scenarios, runner, agent build (prompts, model
# settings, and the output cap are compiled into it), endpoint, prices, or the trial plan.
$agentFiles = @(Get-ChildItem $AgentDir -Recurse -File | Where-Object { $_.FullName -notmatch '\\lib\\' } | Sort-Object FullName | ForEach-Object FullName)
$settings = [ordered]@{
    model = $Model; endpoint = $endpoint; replicates = $Replicates; modes = ($Modes -join ','); seed = $Seed
    inputPrice = $InputPrice; outputPrice = $OutputPrice
    scenarios = (($scenarioFiles | ForEach-Object { Split-Path $_ -Leaf }) -join ',')
    scenarioHash = (Get-Fingerprint $scenarioFiles)
    runnerHash = (Get-Fingerprint @($runner))
    agentHash = (Get-Fingerprint $agentFiles)
    agentLibraries = (Get-Fingerprint @(Get-ChildItem (Join-Path $AgentDir 'lib') -Recurse -File | Sort-Object FullName | ForEach-Object FullName))
}
$settingsFile = Join-Path $Out 'settings.json'
$manifestFile = Join-Path $Out 'manifest.csv'
if (Test-Path $manifestFile) {
    $saved = Get-Content $settingsFile -Raw | ConvertFrom-Json
    foreach ($name in $settings.Keys) {
        if ("$($saved.$name)" -ne "$($settings[$name])") { throw "This run was started with a different $name; use a new -Out." }
    }
    $manifest = @(Import-Csv $manifestFile)
} else {
    # Interleave: each replicate is a shuffled round of every mode and scenario, so drift over a long run
    # affects all cells equally. The shuffle is seeded, and the saved manifest is the source of truth afterwards.
    $random = New-Object System.Random $Seed
    $manifest = New-Object System.Collections.ArrayList
    $order = 0
    for ($r = 1; $r -le $Replicates; $r++) {
        $round = foreach ($mode in $Modes) { foreach ($file in $scenarioFiles) { [pscustomobject]@{ mode = $mode; file = $file } } }
        $round = $round | Sort-Object { $random.Next() }
        foreach ($cell in $round) {
            $order++
            $scenarioName = [IO.Path]::GetFileNameWithoutExtension($cell.file)
            [void]$manifest.Add([pscustomobject]@{
                trial = ('{0:D4}-{1}-{2}-r{3}' -f $order, $cell.mode, $scenarioName, $r)
                mode = $cell.mode; scenario = $scenarioName; scenarioFile = $cell.file; replicate = $r
                events = @(Select-String -Path $cell.file -Pattern '^\s+topic: support\.tickets$').Count
            })
        }
    }
    Write-Json $settings $settingsFile
    $manifest | Export-Csv -Path $manifestFile -NoTypeInformation
}
"Manifest: $($manifest.Count) trials in $Out ($endpoint)"

# --- Spend ------------------------------------------------------------------------------------------------------------
function Get-Spend {
    $total = 0.0
    foreach ($runDir in Get-ChildItem $spendRoot -Directory) {
        $trialsDir = Join-Path $runDir.FullName 'trials'
        if (!(Test-Path $trialsDir)) { continue }
        $runSettings = Get-Content (Join-Path $runDir.FullName 'settings.json') -Raw -ErrorAction SilentlyContinue | ConvertFrom-Json
        if (!$runSettings -or !$runSettings.inputPrice -or !$runSettings.outputPrice) {
            throw "Cannot price spend in $($runDir.FullName): settings.json has no prices."
        }
        $reserve = if ($runSettings.trialCostReserve) { [double]$runSettings.trialCostReserve } else { $TrialCostReserve }
        foreach ($attempt in Get-ChildItem $trialsDir -Recurse -Directory -Filter 'attempt-*') {
            $status = Get-Content (Join-Path $attempt.FullName 'status.json') -Raw -ErrorAction SilentlyContinue | ConvertFrom-Json
            $lines = @(Get-Content (Join-Path $attempt.FullName 'usage.jsonl') -ErrorAction SilentlyContinue)
            $unclear = !$status -or $status.outcome -in 'unknown', 'provider-error', 'rate-limited', 'failed'
            $priced = 0
            foreach ($line in $lines) {
                $entry = $line | ConvertFrom-Json
                if ($entry.outcome -eq 'response' -and $null -ne $entry.inputTokens -and $null -ne $entry.outputTokens) {
                    # Completion tokens include reasoning tokens; prompt tokens include cached ones, priced at the
                    # full input price, which overstates cost when the provider applies a cache discount.
                    $total += ([double]$entry.inputTokens * $runSettings.inputPrice + [double]$entry.outputTokens * $runSettings.outputPrice) / 1e6
                    $priced++
                } else {
                    $unclear = $true
                }
            }
            # Fewer logged responses than tickets means a call the log does not account for.
            if ($status -and ($null -eq $status.events -or $priced -lt [int]$status.events)) { $unclear = $true }
            # A crashed, failed, or incompletely logged attempt may have been billed for calls the log does not show.
            if ($unclear) { $total += $reserve }
        }
    }
    return $total
}
if (!(Get-Content $settingsFile -Raw | ConvertFrom-Json).trialCostReserve) {
    $current = Get-Content $settingsFile -Raw | ConvertFrom-Json
    $current | Add-Member -NotePropertyName trialCostReserve -NotePropertyValue $TrialCostReserve -Force
    Write-Json $current $settingsFile
}

# --- One attempt --------------------------------------------------------------------------------------------------------
function Wait-PortFree([int]$port) {
    for ($t = 0; $t -lt 30; $t++) {
        $client = New-Object Net.Sockets.TcpClient
        try { $client.Connect('127.0.0.1', $port); $client.Close(); Start-Sleep 1 } catch { return } finally { $client.Dispose() }
    }
    throw "port $port is still in use"
}
function Start-Broker {
    Wait-PortFree 9092
    $name = "sj-v2-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
    $id = docker run --detach --rm --name $name --publish 127.0.0.1:9092:9092 apache/kafka:4.1.1
    if ($LASTEXITCODE -ne 0) { throw 'broker failed to start' }
    for ($t = 0; $t -lt 60; $t++) {
        docker exec $id /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 *> $null
        if ($LASTEXITCODE -eq 0) {
            foreach ($topic in 'support.tickets', 'support.triage', 'support.published', 'streamjack.audit') {
                docker exec $id /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create `
                    --partitions 1 --replication-factor 1 --topic $topic *> $null
                if ($LASTEXITCODE -ne 0) { docker stop $id *> $null; throw "could not create topic $topic" }
            }
            return $id
        }
        Start-Sleep 1
    }
    docker stop $id *> $null
    throw 'broker not ready'
}

function Get-Usage($attemptDir) {
    @(Get-Content (Join-Path $attemptDir 'usage.jsonl') -ErrorAction SilentlyContinue | ForEach-Object { $_ | ConvertFrom-Json })
}

function Invoke-Attempt($trial, $attemptDir) {
    $broker = $null; $agent = $null; $runnerStarted = $false
    $status = [ordered]@{ outcome = 'unknown'; reason = ''; events = [int]$trial.events; started = (Get-Date).ToString('o') }
    try {
        $broker = Start-Broker
        $env:KAFKA_BOOTSTRAP_SERVERS = '127.0.0.1:9092'
        $env:STREAMJACK_AGENT_MODEL = 'OPENAI'
        $env:STREAMJACK_AGENT_SECURITY_MODE = $trial.mode
        $env:STREAMJACK_USAGE_LOG = Join-Path $attemptDir 'usage.jsonl'
        $env:OPENAI_MODEL = $Model
        $agent = Start-Process java -ArgumentList '-Dquarkus.http.port=0', '-jar', "`"$agentJar`"" -PassThru `
            -WindowStyle Hidden -RedirectStandardOutput "$attemptDir\agent.log" -RedirectStandardError "$attemptDir\agent.err.log"
        $started = $false
        for ($t = 0; $t -lt 90 -and !$agent.HasExited; $t++) {
            if ((Get-Content "$attemptDir\agent.log" -Raw -ErrorAction SilentlyContinue) -match 'started in') { $started = $true; break }
            Start-Sleep 1
        }
        if (!$started) { $status.outcome = 'setup-failed'; $status.reason = 'agent did not start'; return $status }
        $runnerStarted = $true
        & java -jar $runner --agent "openai-$Model-$($trial.mode)" --bootstrap-servers 127.0.0.1:9092 `
            --scenarios $trial.scenarioFile --timeout-seconds 240 --report "$attemptDir\report.json" `
            --evidence-dir "$attemptDir\evidence" *> "$attemptDir\runner.log"
        $usage = Get-Usage $attemptDir
        $errors = @($usage | Where-Object outcome -eq 'error')
        $responses = @($usage | Where-Object outcome -eq 'response')
        # From here on a request may have been sent, so nothing below is retried except an explicit rate limit
        # that produced no paid response at all.
        if (!(Test-Path "$attemptDir\report.json")) { $status.reason = 'runner wrote no report'; return $status }
        $result = (Get-Content "$attemptDir\report.json" -Raw | ConvertFrom-Json).results[0]
        $evidence = @(Get-ChildItem "$attemptDir\evidence" -Filter '*.json' -ErrorAction SilentlyContinue)
        # Only the runner's fixed text and the agent's validated error code are used, never free agent text.
        $messages = ($result.findings | ForEach-Object message) -join ' '
        if ($result.execution -eq 'COMPLETED' -and $evidence.Count -eq 1 -and !$errors) {
            $status.outcome = 'completed'
        } elseif ($errors -and $responses.Count -eq 0 -and !($errors | Where-Object { $_.error -notmatch 'RateLimit' })) {
            $status.outcome = 'rate-limited'; $status.reason = 'provider rate limit'
        } elseif ($errors) {
            # Authentication, model access, an unsupported request, or a server error: stop and inspect.
            $status.outcome = 'provider-error'; $status.reason = (($errors | ForEach-Object error | Sort-Object -Unique) -join ' ')
        } elseif ($messages -match 'with MODEL_OUTPUT_INVALID') {
            $status.outcome = 'model-output-invalid'; $status.reason = 'agent rejected the model output'
        } elseif ($usage.Count -eq 0) {
            $status.reason = "execution $($result.execution) and no model call was logged"
        } else {
            $status.outcome = 'failed'; $status.reason = "execution $($result.execution)"
        }
        return $status
    } catch {
        $status.outcome = if ($runnerStarted) { 'unknown' } else { 'setup-failed' }
        $status.reason = "harness error: $($_.Exception.Message)"
        return $status
    } finally {
        if ($agent -and !$agent.HasExited) {
            Stop-Process -Id $agent.Id -Force -ErrorAction SilentlyContinue
            $agent.WaitForExit(30000) | Out-Null
        }
        if ($broker) { docker stop $broker *> $null }
        Remove-Item Env:STREAMJACK_USAGE_LOG -ErrorAction SilentlyContinue
        $status.finished = (Get-Date).ToString('o')
    }
}

# Returns a reason to stop the whole run, or $null. A model call the log cannot account for, or the wrong served
# model, means results and spend can no longer be trusted.
function Test-Usage($trial, $attemptDir, $status) {
    $responses = @(Get-Usage $attemptDir | Where-Object outcome -eq 'response')
    # The exact alias, or the alias plus a dated snapshot suffix (for example gpt-6-luna-2026-10-01).
    $servedPattern = '^' + [regex]::Escape($Model) + '(-\d{4}-\d{2}-\d{2})?$'
    foreach ($response in $responses) {
        if ("$($response.model)" -notmatch $servedPattern) { return "served model '$($response.model)' is not '$Model'" }
        if ($null -eq $response.inputTokens -or $null -eq $response.outputTokens) { return 'a response has no token counts' }
    }
    if ($status.outcome -eq 'completed' -and $responses.Count -ne [int]$trial.events) {
        return "expected $($trial.events) model calls, the usage log has $($responses.Count)"
    }
    return $null
}

# --- Main loop ------------------------------------------------------------------------------------------------------------
$retryable = 'setup-failed', 'rate-limited'
# Outcomes that halt the run for a human to inspect: the setup, the key, or the request is wrong, so continuing
# would only spend money on more of the same.
$halting = 'stopped', 'provider-error'
$exitCode = 0; $ranThisInvocation = 0; $unsuccessful = 0
try {
    # A halted run stays halted until a human has inspected it and removed the halting final.json.
    foreach ($existing in Get-ChildItem (Join-Path $Out 'trials') -Recurse -Filter 'final.json' -ErrorAction SilentlyContinue) {
        $state = Read-Outcome $existing.FullName
        if ($state.outcome -in $halting) {
            "Refusing to continue: $($existing.Directory.Name) ended as '$($state.outcome)' ($($state.reason))."
            "Inspect it, fix the cause, and delete $($existing.FullName) to resume."
            $exitCode = 4
            break
        }
    }
    # The manifest is skipped entirely when the run is halted (an empty list, not a return, so the exit code holds).
    $pending = if ($exitCode -eq 0) { $manifest } else { @() }
    :trials foreach ($trial in $pending) {
        $trialDir = Join-Path $Out "trials\$($trial.trial)"
        if (Test-Path "$trialDir\final.json") { [void](Read-Outcome "$trialDir\final.json"); continue }
        New-Item -ItemType Directory -Force -Path $trialDir | Out-Null
        # Rebuild the trial state from what earlier invocations recorded. An attempt without a status crashed while
        # running: it may have been billed, so it is final as 'unknown'.
        $attempts = @(Get-ChildItem $trialDir -Directory -Filter 'attempt-*' | Sort-Object Name)
        $last = $null; $lastDir = $null
        foreach ($previous in $attempts) {
            $statusFile = "$($previous.FullName)\status.json"
            if (!(Test-Path $statusFile)) {
                Write-Json ([ordered]@{ outcome = 'unknown'; reason = 'interrupted'; events = [int]$trial.events }) $statusFile
            }
            $last = Read-Outcome $statusFile; $lastDir = $previous
            if ($last.outcome -eq 'completed') { break }
        }
        $final = $null
        if ($last -and $last.outcome -notin $retryable) {
            # A recovered attempt gets the same usage check as a fresh one before its result is accepted.
            $problem = Test-Usage $trial $lastDir.FullName $last
            if ($problem) {
                Write-Json ([ordered]@{ outcome = 'stopped'; attempt = $attempts.Count; reason = $problem }) "$trialDir\final.json"
                "Stopping the run: $problem. Inspect $($lastDir.FullName)."
                $exitCode = 4; break trials
            }
            $final = [ordered]@{ outcome = $last.outcome; attempt = [int]($lastDir.Name -replace 'attempt-', '') }
        }
        if (!$final -and $MaxTrials -gt 0 -and $ranThisInvocation -ge $MaxTrials) {
            "Stopping after $MaxTrials trial(s) as requested. Rerun the same command to continue."
            break
        }
        for ($n = $attempts.Count + 1; !$final -and $n -le $MaxAttempts; $n++) {
            $spend = Get-Spend
            if ($spend + $TrialCostReserve -gt $CostLimit) {
                "Stopping: spend so far `$$([math]::Round($spend, 4)) plus the trial reserve would exceed the limit of `$$CostLimit."
                "Rerun the same command after raising -CostLimit to resume."
                $exitCode = 3; break trials
            }
            if ($last -and $last.outcome -eq 'rate-limited') { Start-Sleep -Seconds (30 * ($n - 1)) }
            $attemptDir = Join-Path $trialDir ('attempt-{0}' -f $n)
            New-Item -ItemType Directory -Force -Path $attemptDir | Out-Null
            $status = Invoke-Attempt $trial $attemptDir
            Write-Json $status "$attemptDir\status.json"
            "$($trial.trial) attempt ${n}: $($status.outcome) $($status.reason)"
            $problem = Test-Usage $trial $attemptDir $status
            if ($problem) {
                Write-Json ([ordered]@{ outcome = 'stopped'; attempt = $n; reason = $problem }) "$trialDir\final.json"
                "Stopping the run: $problem. Inspect $attemptDir."
                $exitCode = 4; break trials
            }
            $last = [pscustomobject]$status
            if ($status.outcome -notin $retryable) { $final = [ordered]@{ outcome = $status.outcome; attempt = $n; reason = $status.reason } }
        }
        if (!$final) { $final = [ordered]@{ outcome = $last.outcome; attempt = $MaxAttempts; reason = $last.reason } }
        Write-Json $final "$trialDir\final.json"
        $ranThisInvocation++
        if ($final.outcome -ne 'completed') { $unsuccessful++ }
        if ($final.outcome -in $halting) {
            "Stopping the run: $($trial.trial) ended as '$($final.outcome)' ($($final.reason)). Inspect it before resuming."
            $exitCode = 5; break trials
        }
    }
    # Exit 0 only if every trial this invocation ran completed, so a smoke test cannot pass on a failure.
    if ($exitCode -eq 0 -and $unsuccessful -gt 0) { $exitCode = 6 }
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    $finals = @(Get-ChildItem (Join-Path $Out 'trials') -Recurse -Filter 'final.json' -ErrorAction SilentlyContinue |
        ForEach-Object { (Get-Content $_.FullName -Raw | ConvertFrom-Json).outcome })
    $summary = ($finals | Group-Object | ForEach-Object { "$($_.Count) $($_.Name)" }) -join ', '
    "Trials finished: $($finals.Count) of $($manifest.Count) ($summary). Spend across ${spendRoot}: `$$([math]::Round((Get-Spend), 4))"
}
exit $exitCode
