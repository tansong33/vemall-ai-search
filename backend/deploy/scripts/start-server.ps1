param(
    [int]$DockerWaitSeconds = 240
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$composeFile = Join-Path $projectRoot 'compose.yml'
$dockerDesktop = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
$logDirectory = 'E:\ai-search-next-data\logs\host'
$logFile = Join-Path $logDirectory 'startup.log'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

function Write-StartupLog {
    param([string]$Message)
    Add-Content -LiteralPath $logFile -Value ('{0:u} {1}' -f (Get-Date), $Message) -Encoding UTF8
}

function Test-DockerReady {
    & docker info *> $null
    return $LASTEXITCODE -eq 0
}

try {
    Write-StartupLog 'Starting AI Search Next server recovery.'
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

    Push-Location $projectRoot
    try {
        & docker compose -f $composeFile up -d
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose up failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }

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
    Write-StartupLog 'AI Search Next is running.'
} catch {
    Write-StartupLog "ERROR: $($_.Exception.Message)"
    throw
}
