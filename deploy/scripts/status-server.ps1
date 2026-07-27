param(
    [string]$SharedEnvFile = 'C:\ai-search-config\shared.env',
    [string]$AppEnvFile = 'C:\ai-search-config\prod.env',
    [string]$PublicUrl = '',
    [string]$ElasticsearchUrl = 'http://127.0.0.1:19200',
    [string]$ElasticsearchIndex = 'products_v2'
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Show-ComposeStatus {
    param(
        [string]$ComposeFile,
        [string]$ProjectName,
        [string]$EnvFile,
        [switch]$ToolsProfile
    )

    $arguments = @('compose')
    if ($ProjectName) {
        $arguments += @('-p', $ProjectName)
    }
    if ($ToolsProfile) {
        $arguments += @('--profile', 'tools')
    }
    if (Test-Path -LiteralPath $EnvFile) {
        $arguments += @('--env-file', $EnvFile)
    }
    $arguments += @('-f', $ComposeFile, 'ps')
    & docker @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

Write-Output 'Shared infrastructure:'
Show-ComposeStatus `
    -ComposeFile (Join-Path $projectRoot 'compose.shared.yml') `
    -EnvFile $SharedEnvFile `
    -ToolsProfile

Write-Output ''
Write-Output 'Application stack:'
Show-ComposeStatus `
    -ComposeFile (Join-Path $projectRoot 'compose.apps.yml') `
    -ProjectName 'ai-search-prod' `
    -EnvFile $AppEnvFile

Write-Output ''
Write-Output 'Local gateway:'
curl.exe --noproxy '*' -fsS --max-time 15 http://127.0.0.1:18080/health

Write-Output ''
Write-Output 'REST application:'
curl.exe --noproxy '*' -fsS --max-time 20 http://127.0.0.1:18080/actuator/health

Write-Output ''
Write-Output 'Elasticsearch documents:'
curl.exe --noproxy '*' -fsS --max-time 20 `
    "$($ElasticsearchUrl.TrimEnd('/'))/$ElasticsearchIndex/_count"

if ($PublicUrl) {
    Write-Output ''
    Write-Output 'Public gateway:'
    curl.exe --noproxy '*' -sS -o NUL -w 'HTTP %{http_code} %{time_total}s' `
        --max-time 30 "$($PublicUrl.TrimEnd('/'))/"
    Write-Output ''
}
