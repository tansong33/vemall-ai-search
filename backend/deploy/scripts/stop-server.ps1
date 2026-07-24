param(
    [string]$SharedEnvFile = 'C:\ai-search-config\shared.env',
    [string]$AppEnvFile = 'C:\ai-search-config\prod.env',
    [switch]$KeepSharedServices
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))

function Invoke-ComposeStop {
    param(
        [string]$ComposeFile,
        [string]$ProjectName,
        [string]$EnvFile,
        [switch]$AdminProfile
    )

    $arguments = @('compose')
    if ($ProjectName) {
        $arguments += @('-p', $ProjectName)
    }
    if ($AdminProfile) {
        $arguments += @('--profile', 'admin')
    }
    if (Test-Path -LiteralPath $EnvFile) {
        $arguments += @('--env-file', $EnvFile)
    }
    $arguments += @('-f', $ComposeFile, 'stop')
    & docker @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

Invoke-ComposeStop `
    -ComposeFile (Join-Path $projectRoot 'compose.apps.yml') `
    -ProjectName 'ai-search-prod' `
    -EnvFile $AppEnvFile

if (-not $KeepSharedServices) {
    Invoke-ComposeStop `
        -ComposeFile (Join-Path $projectRoot 'compose.shared.yml') `
        -EnvFile $SharedEnvFile `
        -AdminProfile
}
