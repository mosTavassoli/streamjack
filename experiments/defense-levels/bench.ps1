# Runs all bundled scenarios against one Ollama model at several security modes and appends raw results to a CSV.
# Disposable local Kafka only. Each run starts a fresh broker and agent process.
param(
    [int]$Runs = 20,
    [string[]]$Modes = @('VULNERABLE', 'PROMPT_ONLY', 'HARDENED'),
    [Parameter(Mandatory)][string]$Model,
    # The Quarkus extension fixes the model id at build time, so each model needs its own agent build (see run.ps1).
    [Parameter(Mandatory)][string]$AgentDir,
    [Parameter(Mandatory)][string]$Out,
    [string]$OllamaHost = '127.0.0.1:11434'
)
# Native tools write normal warnings to stderr; rely on exit codes and explicit throws instead.
$ErrorActionPreference = 'Continue'
# Point the agent and the ollama CLI at the same server, so the per-run model check inspects the server that answered.
$env:OLLAMA_HOST = $OllamaHost
$env:OLLAMA_BASE_URL = "http://$OllamaHost"
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runner = Join-Path $root 'streamjack-kafka\target\streamjack-kafka-0.3.0-SNAPSHOT-all.jar'
$agentJar = Join-Path $AgentDir 'quarkus-run.jar'
$ollama = (Get-Command ollama -ErrorAction SilentlyContinue).Source
if (!$ollama) { $ollama = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe' }
foreach ($required in $runner, $agentJar, $ollama) { if (!(Test-Path $required)) { throw "Not found: $required" } }
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$csv = Join-Path $Out 'raw.csv'
# Never mix runs: an existing CSV would make repeated run numbers look like independent observations.
if (Test-Path $csv) { throw "Output already contains results: $csv. Use a new -Out directory." }

function Start-Broker {
    $name = "sj-bench-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
    $id = docker run --detach --rm --name $name --publish 127.0.0.1:9092:9092 apache/kafka:4.1.1
    if ($LASTEXITCODE -ne 0) { throw 'broker failed to start' }
    for ($t = 0; $t -lt 30; $t++) {
        docker exec $id /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 *> $null
        if ($LASTEXITCODE -eq 0) {
            # Create every topic up front; a missing output topic delays the first write and skews results.
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

function Write-Row($row) { $row | Export-Csv -Path $csv -Append -NoTypeInformation }

# Interleave modes so slowdowns or drift during a long run affect every mode equally.
for ($i = 1; $i -le $Runs; $i++) {
    foreach ($mode in $Modes) {
        $tag = "$mode-$i"
        $broker = $null; $agent = $null
        $clock = [Diagnostics.Stopwatch]::StartNew()
        try {
            $broker = Start-Broker
            $env:KAFKA_BOOTSTRAP_SERVERS = '127.0.0.1:9092'
            $env:STREAMJACK_AGENT_MODEL = 'OLLAMA'
            $env:STREAMJACK_AGENT_SECURITY_MODE = $mode
            $agent = Start-Process java -ArgumentList '-Dquarkus.http.port=0', '-jar', "`"$agentJar`"" -PassThru `
                -WindowStyle Hidden -RedirectStandardOutput "$Out\agent-$tag.log" -RedirectStandardError "$Out\agent-$tag.err.log"
            $started = $false
            for ($t = 0; $t -lt 90 -and !$agent.HasExited; $t++) {
                if ((Get-Content "$Out\agent-$tag.log" -Raw -ErrorAction SilentlyContinue) -match 'started in') { $started = $true; break }
                Start-Sleep 1
            }
            if (!$started) { throw 'agent did not start' }
            # A report left by an earlier invocation must not be read as this run's result.
            Remove-Item "$Out\report-$tag.json" -ErrorAction SilentlyContinue
            & java -jar $runner --agent "ollama-$Model-$mode" --bootstrap-servers 127.0.0.1:9092 `
                --scenarios (Join-Path $root 'scenarios') --timeout-seconds 300 --report "$Out\report-$tag.json" *> "$Out\runner-$tag.log"
            if (!(Test-Path "$Out\report-$tag.json")) { throw 'runner wrote no report; see runner log' }
            # Guard against the build-time model trap: count the run only if Ollama served the expected model.
            $loaded = @(& $ollama ps | Select-Object -Skip 1 | ForEach-Object { ($_ -split '\s+')[0] } | Where-Object { $_ })
            if ($loaded -notcontains $Model) { throw "wrong model served: expected $Model, Ollama had [$($loaded -join ', ')]" }
            $report = Get-Content "$Out\report-$tag.json" -Raw | ConvertFrom-Json
            foreach ($r in $report.results) {
                $failed = @($r.findings | Where-Object { -not $_.passed })
                $published = @($failed | Where-Object { $_.check -eq 'forbidden-tool' } |
                    ForEach-Object { if ($_.message -match 'while processing event (\S+)') { $Matches[1] } }) | Sort-Object -Unique
                Write-Row ([pscustomobject]@{
                    model = $Model; mode = $mode; run = $i; scenario = $r.scenarioId
                    execution = $r.execution; security = $r.security; utility = $r.utility
                    publishedEvents = ($published -join ' ')
                    canaryLeak = [bool]($failed | Where-Object check -eq 'canary-leak')
                    duplicateSideEffect = [bool]($failed | Where-Object check -eq 'idempotent-tool')
                    seconds = [math]::Round($clock.Elapsed.TotalSeconds, 1)
                })
            }
            "$tag done in $([math]::Round($clock.Elapsed.TotalSeconds))s"
        } catch {
            Write-Row ([pscustomobject]@{ model = $Model; mode = $mode; run = $i; scenario = '*'; execution = 'ERROR'
                security = ''; utility = ''; publishedEvents = $_.Exception.Message; canaryLeak = $false
                duplicateSideEffect = $false; seconds = 0 })
            "$tag ERROR: $($_.Exception.Message)"
        } finally {
            if ($agent -and !$agent.HasExited) { Stop-Process -Id $agent.Id -Force -ErrorAction SilentlyContinue }
            if ($broker) { docker stop $broker *> $null }
        }
    }
}
"Raw data: $csv"
