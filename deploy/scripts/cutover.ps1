param(
    [Parameter(Mandatory = $true)]
    [string]$OldRoot,
    [string]$OldComposeFile = 'docker-compose.prod.yml',
    [int]$HealthWaitSeconds = 420
)

$ErrorActionPreference = 'Stop'
$newRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$oldRoot = [System.IO.Path]::GetFullPath($OldRoot)
$newCompose = Join-Path $newRoot 'compose.yml'
$stageCompose = Join-Path $newRoot 'compose.staging.yml'
$oldCompose = Join-Path $oldRoot $OldComposeFile

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
        try {
            $gateway = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/health' `
                -TimeoutSec 10
            $application = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/actuator/health' `
                -TimeoutSec 15
            if ($gateway.status -eq 'ok' -and $application.status -eq 'UP') { return }
        } catch {}
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
