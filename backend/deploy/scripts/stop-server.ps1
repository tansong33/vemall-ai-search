$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$composeFile = Join-Path $projectRoot 'compose.yml'

Push-Location $projectRoot
try {
    docker compose -f $composeFile stop
} finally {
    Pop-Location
}
