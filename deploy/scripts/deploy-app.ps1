param(
    [ValidatePattern('^$|^sha-[0-9a-f]{40}$')]
    [string]$ImageTag = '',

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-zA-Z0-9-]+$')]
    [string]$RegistryOwner,

    [Parameter(Mandatory = $true)]
    [string]$EnvFile,

    [string]$HealthBaseUrl = 'http://127.0.0.1:18080',
    [string]$ProjectName = 'ai-search-prod',
    [string]$DeploymentRoot = '',
    [ValidatePattern('^$|^(dev|main)$')]
    [string]$GitRef = '',
    [ValidatePattern('^$|^[0-9a-f]{40}$')]
    [string]$GitCommit = '',
    [int]$HealthTimeoutSeconds = 240,
    [switch]$Rollback
)

$ErrorActionPreference = 'Stop'
$sourceProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$projectRoot = if ($DeploymentRoot) {
    [System.IO.Path]::GetFullPath($DeploymentRoot)
} else {
    $sourceProjectRoot
}
$composeFile = Join-Path $projectRoot 'compose.apps.yml'
$resolvedEnvFile = [System.IO.Path]::GetFullPath($EnvFile)
$backupFile = "$resolvedEnvFile.previous"
$failedFile = "$resolvedEnvFile.failed"
$lockFile = "$resolvedEnvFile.deploy.lock"
$healthRoot = $HealthBaseUrl.TrimEnd('/')
$lockStream = $null

function Invoke-DockerCompose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    & docker compose `
        -p $ProjectName `
        --env-file $resolvedEnvFile `
        -f $composeFile `
        @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Invoke-Git {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    & git -C $projectRoot @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Sync-DeploymentRepository {
    if (-not $GitRef -and -not $GitCommit) {
        return
    }
    if (-not $GitRef -or -not $GitCommit) {
        throw 'GitRef and GitCommit must be provided together.'
    }
    if ($ImageTag -ne "sha-$GitCommit") {
        throw 'ImageTag must match GitCommit.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot '.git'))) {
        throw "Deployment root is not a Git repository: $projectRoot"
    }

    $changes = @(& git -C $projectRoot status --porcelain)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect the deployment repository.'
    }
    if ($changes.Count -gt 0) {
        throw "Deployment repository contains local changes: $projectRoot"
    }

    Invoke-Git fetch --prune origin `
        "+refs/heads/$GitRef`:refs/remotes/origin/$GitRef"
    & git -C $projectRoot show-ref --verify --quiet "refs/heads/$GitRef"
    if ($LASTEXITCODE -eq 0) {
        Invoke-Git checkout $GitRef
    } else {
        Invoke-Git checkout -b $GitRef --track "origin/$GitRef"
    }
    Invoke-Git merge --ff-only "origin/$GitRef"

    $actualCommit = (& git -C $projectRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $actualCommit -ne $GitCommit) {
        throw "Deployment repository is at $actualCommit, expected $GitCommit."
    }
    Write-Host "Deployment repository synchronized to $GitRef at $GitCommit"
}

function Set-DotEnvValue {
    param(
        [string]$Path,
        [string]$Name,
        [string]$Value
    )

    $lines = [System.Collections.Generic.List[string]]::new()
    if (Test-Path -LiteralPath $Path) {
        foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
            $lines.Add($line)
        }
    }

    $replacement = "$Name=$Value"
    $found = $false
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match "^\s*$([regex]::Escape($Name))=") {
            $lines[$index] = $replacement
            $found = $true
        }
    }
    if (-not $found) {
        $lines.Add($replacement)
    }

    $temporaryPath = "$Path.tmp.$([guid]::NewGuid().ToString('N'))"
    [System.IO.File]::WriteAllLines(
        $temporaryPath,
        $lines,
        [System.Text.UTF8Encoding]::new($false)
    )
    Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
}

function Set-ApplicationImages {
    param([string]$Tag)

    $prefix = "ghcr.io/$($RegistryOwner.ToLowerInvariant())/vemall-ai-search"
    Set-DotEnvValue $resolvedEnvFile 'AI_SEARCH_REST_IMAGE' "$prefix-rest`:$Tag"
    Set-DotEnvValue $resolvedEnvFile 'FRONTEND_IMAGE' "$prefix-frontend`:$Tag"
    Set-DotEnvValue $resolvedEnvFile 'GATEWAY_IMAGE' "$prefix-gateway`:$Tag"
}

function Wait-ApplicationHealth {
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    do {
        try {
            $gateway = Invoke-RestMethod `
                -Uri "$healthRoot/health" `
                -Method Get `
                -TimeoutSec 10
            $application = Invoke-RestMethod `
                -Uri "$healthRoot/actuator/health" `
                -Method Get `
                -TimeoutSec 15
            if ($gateway.status -eq 'ok' -and $application.status -eq 'UP') {
                Write-Host "Application is healthy: $healthRoot"
                return
            }
        } catch {
            Write-Host "Waiting for application health: $($_.Exception.Message)"
        }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)

    throw "Application did not become healthy within $HealthTimeoutSeconds seconds."
}

function Restore-PreviousDeployment {
    if (-not (Test-Path -LiteralPath $backupFile)) {
        throw "Rollback file does not exist: $backupFile"
    }

    if (Test-Path -LiteralPath $resolvedEnvFile) {
        Copy-Item -LiteralPath $resolvedEnvFile -Destination $failedFile -Force
    }
    Copy-Item -LiteralPath $backupFile -Destination $resolvedEnvFile -Force
    Invoke-DockerCompose config --quiet
    Invoke-DockerCompose pull
    Invoke-DockerCompose up -d --no-build --remove-orphans
    Wait-ApplicationHealth
    Write-Host "Rollback completed from $backupFile"
}

if (-not (Test-Path -LiteralPath $composeFile)) {
    if (-not $GitRef) {
        throw "Compose file does not exist: $composeFile"
    }
}
if (-not (Test-Path -LiteralPath $resolvedEnvFile)) {
    throw "Deployment environment file does not exist: $resolvedEnvFile"
}
if (-not $Rollback -and -not $ImageTag) {
    throw 'ImageTag is required unless Rollback is used.'
}

try {
    $lockStream = [System.IO.File]::Open(
        $lockFile,
        [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None
    )

    if ($Rollback) {
        Restore-PreviousDeployment
        exit 0
    }

    Sync-DeploymentRepository
    if (-not (Test-Path -LiteralPath $composeFile)) {
        throw "Compose file does not exist after repository synchronization: $composeFile"
    }

    Copy-Item -LiteralPath $resolvedEnvFile -Destination $backupFile -Force
    Set-ApplicationImages $ImageTag

    try {
        Invoke-DockerCompose config --quiet
        Invoke-DockerCompose pull
        Invoke-DockerCompose up -d --no-build --remove-orphans
        Wait-ApplicationHealth
        Write-Host "Deployment completed: $ImageTag"
    } catch {
        $deployError = $_
        Write-Warning "Deployment failed; restoring previous image references."
        try {
            Restore-PreviousDeployment
        } catch {
            throw "Deployment failed ($($deployError.Exception.Message)); rollback also failed ($($_.Exception.Message))."
        }
        throw "Deployment failed and was rolled back: $($deployError.Exception.Message)"
    }
} finally {
    if ($lockStream) {
        $lockStream.Dispose()
    }
}
