$ErrorActionPreference = 'Stop'
$revision = 'a4e5d675ba46003bba89b97f115001bad3263bfd'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runner = Join-Path $root 'streamjack-kafka\target\streamjack-kafka-0.3.0-SNAPSHOT-all.jar'
$java = (Get-Command java.exe).Source
$mvn = (Get-Command mvn.cmd).Source
$dockerContext = docker context show
if ($LASTEXITCODE -ne 0 -or !$dockerContext) { throw 'Could not determine Docker context' }
$dockerHost = docker context inspect $dockerContext --format '{{.Endpoints.docker.Host}}'
if ($LASTEXITCODE -ne 0 -or $dockerHost -notlike 'npipe://*') { throw 'Use a local Windows Docker context' }

function Invoke-Docker {
    & docker --context $dockerContext @args
    if ($LASTEXITCODE -ne 0) { throw "Docker failed: $($args -join ' ')" }
}

if ((Invoke-Docker info --format '{{.OSType}}') -ne 'linux') { throw 'Docker must be running Linux containers' }
if (Get-NetTCPConnection -State Listen -LocalPort 9092,18093 -ErrorAction SilentlyContinue) {
    throw 'Free local ports 9092 and 18093 before running this example'
}

$id = [Guid]::NewGuid().ToString('N')
$scratch = Join-Path ([IO.Path]::GetTempPath()) "streamjack-workshop-$id"
$null = New-Item -ItemType Directory -Path $scratch
$checkout = Join-Path $scratch 'workshop'
$workshop = Join-Path $checkout 'section-3\step-04'
Write-Host "Pilot work and reports: $scratch"

git init -q $checkout
if ($LASTEXITCODE -ne 0) { throw 'Could not initialize the temporary workshop checkout' }
git -C $checkout -c fetch.recurseSubmodules=false fetch --depth=1 `
    https://github.com/quarkusio/quarkus-workshop-langchain4j.git $revision
if ($LASTEXITCODE -ne 0) { throw 'Could not fetch the pinned workshop revision' }
git -C $checkout checkout --detach $revision
if ($LASTEXITCODE -ne 0) { throw 'Could not check out the pinned workshop revision' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'PilotPlanAdapter.java') `
    -Destination (Join-Path $workshop 'src\main\java\com\tripplanner\agentic\flow')

& $mvn -f (Join-Path $root 'pom.xml') --batch-mode --no-transfer-progress -pl streamjack-kafka -am -DskipTests package
if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $runner)) { throw 'Could not build Streamjack' }
& $mvn -f (Join-Path $workshop 'pom.xml') --batch-mode --no-transfer-progress '-Dmaven.test.skip=true' package
if ($LASTEXITCODE -ne 0) { throw 'Could not build the workshop fixture' }

function Invoke-Pilot([string]$case, [string]$scenarioId) {
    $name = "streamjack-pilot-$id-$case"
    $out = Join-Path $scratch $case
    $null = New-Item -ItemType Directory -Path $out
    $broker = $null
    $agent = $null
    $bridge = $null
    try {
        $broker = Invoke-Docker run --detach --rm --name $name --publish 127.0.0.1:9092:9092 apache/kafka:4.1.1
        $ready = $false
        for ($i = 0; $i -lt 20; $i++) {
            $null = docker --context $dockerContext exec $broker /bin/sh -c `
                'exec /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1'
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Seconds 1
        }
        if (!$ready) { throw 'Disposable Kafka did not become ready' }
        foreach ($topic in 'flow-in', 'flow-out', 'pilot.trip.unexpected') {
            Invoke-Docker exec $broker /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 `
                --create --partitions 1 --replication-factor 1 --topic $topic
        }
        $agentArgs = @('-Dquarkus.http.host=127.0.0.1', '-Dquarkus.http.port=18093',
            '-Dkafka.bootstrap.servers=127.0.0.1:9092', '-Dquarkus.kafka.devservices.enabled=false',
            '-Dmp.messaging.incoming.flow-in.auto.offset.reset=earliest',
            '-Dquarkus.langchain4j.openai.base-url=http://127.0.0.1:1/v1',
            '-Dquarkus.langchain4j.openai.enhancedModel.base-url=http://127.0.0.1:1/v1',
            '-Dquarkus.langchain4j.openai.api-key=local-fixture-only',
            '-Dquarkus.langchain4j.openai.enhancedModel.api-key=local-fixture-only',
            '-Dtrip.planning.timeout=20s', '-jar', 'target/quarkus-app/quarkus-run.jar')
        $agent = Start-Process $java -ArgumentList $agentArgs -WorkingDirectory $workshop -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput "$out\workshop.log" -RedirectStandardError "$out\workshop.err.log"
        $ready = $false
        for ($i = 0; $i -lt 30 -and !$agent.HasExited; $i++) {
            curl.exe --noproxy '*' --silent --fail --max-time 2 --output NUL 'http://127.0.0.1:18093/'
            if ($LASTEXITCODE -eq 0 -and !$agent.HasExited) { $ready = $true; break }
            Start-Sleep -Seconds 1
        }
        if (!$ready) { throw "Workshop did not start; see $out" }

        $adapter = Join-Path $PSScriptRoot 'StreamjackTripPilot.java'
        $bridge = Start-Process $java -ArgumentList '-cp', "`"$runner`"", "`"$adapter`"", $case `
            -PassThru -WindowStyle Hidden -RedirectStandardOutput "$out\adapter.log" `
            -RedirectStandardError "$out\adapter.err.log"
        & $java -jar $runner --agent quarkus-workshop-step-04 --scenarios (Join-Path $PSScriptRoot "$case.yaml") `
            --bootstrap-servers 127.0.0.1:9092 --quiet-period-millis 3000 --timeout-seconds 50 --report "$out\report.json"
        $code = $LASTEXITCODE
        $report = if (Test-Path -LiteralPath "$out\report.json") { Get-Content -Raw "$out\report.json" | ConvertFrom-Json }
        $result = $report.results | Select-Object -First 1
        if ($code -ne 0 -or $bridge.HasExited -or $agent.HasExited -or $report.results.Count -ne 1 `
            -or $result.scenarioId -ne $scenarioId -or $result.execution -ne 'COMPLETED' `
            -or $result.security -ne 'SECURE' -or $result.utility -ne 'MET') {
            throw "$scenarioId did not reach COMPLETED / SECURE / MET (runner exit $code); see $out"
        }
    } finally {
        foreach ($process in $bridge, $agent) {
            if ($process -and !$process.HasExited) {
                Stop-Process -InputObject $process -ErrorAction SilentlyContinue
                $process.WaitForExit(5000) | Out-Null
            }
        }
        if ($broker) { Invoke-Docker stop $broker | Out-Null }
    }
}

Invoke-Pilot 'pending' 'SJ-901'
Invoke-Pilot 'denial' 'SJ-902'
Invoke-Pilot 'approval' 'SJ-903'
Write-Host "Reports under ${scratch}: pending\report.json, denial\report.json, approval\report.json"
