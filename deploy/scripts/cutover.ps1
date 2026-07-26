param(
    [int]$HealthWaitSeconds = 420
)

$ErrorActionPreference = 'Stop'
$newRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$oldRoot = 'D:\ai-search'
$newCompose = Join-Path $newRoot 'compose.yml'
$stageCompose = Join-Path $newRoot 'compose.staging.yml'
$oldCompose = Join-Path $oldRoot 'docker-compose.prod.yml'

function Invoke-Compose {
    param([string]$Root, [string]$File, [string[]]$Arguments)
    Push-Location $Root
    try {
        & docker compose -f $File @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Wait-NewGateway {
    $deadline = (Get-Date).AddSeconds($HealthWaitSeconds)
    while ((Get-Date) -lt $deadline) {
        $health = docker inspect -f '{{.State.Health.Status}}' ai-search-next-gateway 2>$null
        if ($health -eq 'healthy') {
            $count = docker exec ai-search-next-es curl -fsS `
                http://127.0.0.1:9200/products_v2/_count 2>$null
            if ($LASTEXITCODE -eq 0 -and $count -match '1091174') { return }
        }
        Start-Sleep -Seconds 5
    }
    throw "New gateway did not become healthy within $HealthWaitSeconds seconds."
}

Write-Output 'Building final images while the old service remains online...'
Invoke-Compose $newRoot $newCompose @('build')
Write-Output 'Stopping staging containers...'
Invoke-Compose $newRoot $stageCompose @('down')

try {
    Write-Output 'Stopping old AI Search containers...'
    Invoke-Compose $oldRoot $oldCompose @('down')
    Write-Output 'Starting the new project on port 18080...'
    Invoke-Compose $newRoot $newCompose @('up', '-d')
    Wait-NewGateway

    $response = Invoke-WebRequest -Uri 'http://127.0.0.1:18080/health' `
        -UseBasicParsing -TimeoutSec 20
    if ($response.StatusCode -ne 200) { throw 'New local gateway validation failed.' }

    & (Join-Path $PSScriptRoot 'install-scheduled-task-actions.ps1')
    Write-Output 'CUTOVER_OK'
} catch {
    Write-Warning "Cutover failed: $($_.Exception.Message)"
    Write-Warning 'Rolling back to the old project...'
    try { Invoke-Compose $newRoot $newCompose @('down') } catch {}
    Invoke-Compose $oldRoot $oldCompose @('up', '-d')
    throw
}
