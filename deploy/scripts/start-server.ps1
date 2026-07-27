param(
    [int]$DockerWaitSeconds = 240,
    [string]$SharedEnvFile = 'C:\ai-search-config\shared.env',
    [string]$AppEnvFile = 'C:\ai-search-config\prod.env',
    [string]$LogDirectory = 'E:\ai-search-next-data\logs\host',
    [switch]$WithTools
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$sharedComposeFile = Join-Path $projectRoot 'compose.shared.yml'
$appComposeFile = Join-Path $projectRoot 'compose.apps.yml'
$dockerDesktop = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
$logFile = Join-Path $LogDirectory 'startup.log'
New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null

function Write-StartupLog {
    param([string]$Message)
    Add-Content -LiteralPath $logFile -Value ('{0:u} {1}' -f (Get-Date), $Message) -Encoding UTF8
}

function Test-DockerReady {
    & docker info *> $null
    return $LASTEXITCODE -eq 0
}

function Invoke-Compose {
    param(
        [string]$ComposeFile,
        [string]$ProjectName,
        [string]$EnvFile,
        [string[]]$Arguments,
        [switch]$ToolsProfile
    )

    $dockerArguments = [System.Collections.Generic.List[string]]::new()
    $dockerArguments.Add('compose')
    if ($ProjectName) {
        $dockerArguments.Add('-p')
        $dockerArguments.Add($ProjectName)
    }
    if ($ToolsProfile) {
        $dockerArguments.Add('--profile')
        $dockerArguments.Add('tools')
    }
    if (Test-Path -LiteralPath $EnvFile) {
        $dockerArguments.Add('--env-file')
        $dockerArguments.Add($EnvFile)
    } else {
        Write-StartupLog "Environment file not found; using Compose defaults: $EnvFile"
    }
    $dockerArguments.Add('-f')
    $dockerArguments.Add($ComposeFile)
    foreach ($argument in $Arguments) {
        $dockerArguments.Add($argument)
    }

    & docker @dockerArguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($dockerArguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Wait-GatewayHealth {
    param([int]$TimeoutSeconds = 240)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $gateway = Invoke-RestMethod `
                -Uri 'http://127.0.0.1:18080/health' `
                -TimeoutSec 10
            $application = Invoke-RestMethod `
                -Uri 'http://127.0.0.1:18080/actuator/health' `
                -TimeoutSec 15
            if ($gateway.status -eq 'ok' -and $application.status -eq 'UP') {
                return
            }
        } catch {
            Write-StartupLog "Waiting for gateway health: $($_.Exception.Message)"
        }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    throw "Gateway did not become healthy within $TimeoutSeconds seconds."
}

try {
    Write-StartupLog 'Starting Vemall AI Search server recovery.'
    if (-not (Test-DockerReady)) {
        if (-not (Test-Path -LiteralPath $dockerDesktop)) {
            throw "Docker Desktop executable not found: $dockerDesktop"
        }
        if (-not (Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue)) {
            Start-Process -FilePath $dockerDesktop -WindowStyle Hidden
            Write-StartupLog 'Docker Desktop launched.'
        }
        $deadline = (Get-Date).AddSeconds($DockerWaitSeconds)
        while ((Get-Date) -lt $deadline -and -not (Test-DockerReady)) {
            Start-Sleep -Seconds 5
        }
    }
    if (-not (Test-DockerReady)) {
        throw "Docker did not become ready within $DockerWaitSeconds seconds."
    }

    Invoke-Compose `
        -ComposeFile $sharedComposeFile `
        -EnvFile $SharedEnvFile `
        -Arguments @('up', '-d') `
        -ToolsProfile:$WithTools
    Invoke-Compose `
        -ComposeFile $appComposeFile `
        -ProjectName 'ai-search-prod' `
        -EnvFile $AppEnvFile `
        -Arguments @('up', '-d', '--no-build')

    Wait-GatewayHealth

    $tunnelProcess = Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -eq 'cloudflared.exe' -and $_.CommandLine -like '*run ai-search*'
        }
    if (-not $tunnelProcess) {
        $task = Get-ScheduledTask -TaskName 'Cloudflare Tunnel ai-search' -ErrorAction SilentlyContinue
        if ($task) {
            Start-ScheduledTask -TaskName 'Cloudflare Tunnel ai-search'
            Write-StartupLog 'Cloudflare Tunnel task started.'
        }
    }

    $scheduleUpdater = Join-Path $PSScriptRoot 'update-maintenance-schedule.ps1'
    if (Test-Path -LiteralPath $scheduleUpdater) {
        & $scheduleUpdater -Quiet
    }
    Write-StartupLog 'Vemall AI Search is healthy.'
} catch {
    Write-StartupLog "ERROR: $($_.Exception.Message)"
    throw
}
