# Builds the sample agent for the OpenAI provider (fixed at build time) and copies it to -Dest, so every model has
# its own agent build. No API key is needed to build.
param([Parameter(Mandatory)][string]$Model, [Parameter(Mandatory)][string]$Dest)
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$env:STREAMJACK_LLM_PROVIDER = 'openai'
$env:OPENAI_MODEL = $Model
mvn -f (Join-Path $root 'pom.xml') --batch-mode --no-transfer-progress -q -pl sample-agent -am package -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'agent build failed' }
Remove-Item Env:STREAMJACK_LLM_PROVIDER, Env:OPENAI_MODEL
if (Test-Path $Dest) { Remove-Item $Dest -Recurse -Force }
Copy-Item (Join-Path $root 'sample-agent\target\quarkus-app') $Dest -Recurse
"Agent for $Model ready: $Dest"
