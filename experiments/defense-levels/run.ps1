# Builds one agent per model (the model id is fixed at build time), then benchmarks each model at three security modes.
param(
    [string[]]$Models = @('qwen3:1.7b', 'llama3.2:3b'),
    [int]$Runs = 20,
    [string]$Out = (Join-Path ([IO.Path]::GetTempPath()) ("streamjack-defense-levels-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))),
    # The local Ollama server used by both the agent and the ollama CLI; pinning both avoids checking a different server.
    [string]$OllamaHost = '127.0.0.1:11434'
)
$ErrorActionPreference = 'Continue'
$env:OLLAMA_HOST = $OllamaHost
$env:OLLAMA_BASE_URL = "http://$OllamaHost"
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ollama = (Get-Command ollama -ErrorAction SilentlyContinue).Source
if (!$ollama) { $ollama = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe' }
New-Item -ItemType Directory -Force -Path $Out | Out-Null
"Results: $Out"

foreach ($model in $Models) {
    $slug = $model -replace '[:.]', '-'
    $dir = Join-Path $Out $slug
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    & $ollama pull $model *> $null
    if ($LASTEXITCODE -ne 0) { "Could not pull $model"; continue }

    $env:OLLAMA_MODEL = $model
    mvn -f (Join-Path $root 'pom.xml') --batch-mode --no-transfer-progress -q -pl sample-agent -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { "Agent build failed for $model"; continue }
    Remove-Item Env:OLLAMA_MODEL
    $agentDir = Join-Path $dir 'agent'
    Copy-Item (Join-Path $root 'sample-agent\target\quarkus-app') $agentDir -Recurse

    # Unload only this benchmark's other models, so the per-run check cannot be satisfied by a model left loaded
    # from a previous step. Models this benchmark does not use are left alone.
    foreach ($other in $Models | Where-Object { $_ -ne $model }) { & $ollama stop $other *> $null }
    @{ model = $model; ollama = (& $ollama --version) -join ' '
       modelInfo = (& $ollama list | Select-String ([regex]::Escape($model))).Line
       streamjack = (git -C $root rev-parse --short HEAD); started = (Get-Date).ToString('s') } |
        ConvertTo-Json | Set-Content (Join-Path $dir 'environment.json')
    & (Join-Path $PSScriptRoot 'bench.ps1') -Runs $Runs -Model $model -AgentDir $agentDir -Out $dir | Select-Object -Last 1
}
# Restore the repository build to its default model.
mvn -f (Join-Path $root 'pom.xml') --batch-mode --no-transfer-progress -q -pl sample-agent -am package -DskipTests
& (Join-Path $PSScriptRoot 'analyze.ps1') -Csv (Get-ChildItem $Out -Recurse -Filter raw.csv).FullName
